#!/usr/bin/env python3
"""Sample an already-running isolated desktop app by log markers or typed phases.

UI interaction is manual or driven by the separate benchmark app. Each phase
records macOS physical footprint as well as RSS, because swapped/compressed
pages can make RSS look misleadingly small.
"""
import argparse
import json
import os
import re
import subprocess
import threading
import time
from pathlib import Path


def mib(value):
    match = re.fullmatch(r"([\d.]+)([KMGT]?)", value)
    if not match:
        return None
    return float(match[1]) * {"": 1 / 1048576, "K": 1 / 1024, "M": 1, "G": 1024, "T": 1048576}[match[2]]


def snapshot(pid):
    try:
        result = subprocess.run(["vmmap", "-summary", str(pid)], capture_output=True, text=True, timeout=30)
    except subprocess.TimeoutExpired:
        return {"error": "vmmap exceeded 30 seconds"}
    if result.returncode:
        return {"error": result.stderr.strip()}
    values = {}
    for label, key in [("Physical footprint", "footprintMiB"), ("Physical footprint (peak)", "lifetimePeakFootprintMiB")]:
        match = re.search(re.escape(label) + r":\s*([\d.]+[KMGT]?)", result.stdout)
        if match:
            values[key] = mib(match[1])
    for label in ["JS VM Reservations", "WebKit Malloc", "owned unmapped (graphics)"]:
        match = re.search(r"^" + re.escape(label) + r"\s+([\d.]+[KMGT]?)\s+([\d.]+[KMGT]?)\s+([\d.]+[KMGT]?)\s+([\d.]+[KMGT]?)", result.stdout, re.M)
        if match:
            values[label] = dict(zip(["virtualMiB", "residentMiB", "dirtyMiB", "swappedMiB"], map(mib, match.groups())))
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-pid", type=int, required=True)
    parser.add_argument("--web-pid", type=int, required=True)
    parser.add_argument("--gpu-pid", type=int)
    parser.add_argument("--network-pid", type=int)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--phase-log", type=Path, help="Follow SHILLING_MEMORY markers from the isolated fixture's Tauri log")
    args = parser.parse_args()
    started = time.monotonic()
    processes = {"host": args.host_pid, "webContent": args.web_pid}
    if args.gpu_pid:
        processes["gpu"] = args.gpu_pid
    if args.network_pid:
        processes["network"] = args.network_pid
    result = {"processIds": processes, "rssSamples": [], "phases": [], "diagnostics": [], "complete": False}
    stop = threading.Event()

    def sample():
        while not stop.is_set():
            output = subprocess.run(["ps", "-o", "pid=,rss=", "-p", ",".join(map(str, processes.values()))], capture_output=True, text=True)
            sizes = {int(pid): int(rss) / 1024 for pid, rss in (line.split() for line in output.stdout.splitlines())}
            result["rssSamples"].append({"seconds": round(time.monotonic() - started, 3), **{name + "MiB": sizes.get(pid) for name, pid in processes.items()}})
            stop.wait(0.25)

    thread = threading.Thread(target=sample)
    thread.start()
    print("Sampling. Enter a phase name for footprint snapshots; 'quit' saves and exits.", flush=True)
    log = args.phase_log.open() if args.phase_log else None
    if log:
        log.seek(0, 2)
    try:
        while True:
            if log:
                line = log.readline()
                if not line:
                    # Tauri rotates its small log during verbose Store/RTC work.
                    # Follow the pathname, not the retired file descriptor.
                    try:
                        path_stat = args.phase_log.stat()
                    except FileNotFoundError:
                        time.sleep(0.2)
                        continue
                    if path_stat.st_ino != os.fstat(log.fileno()).st_ino:
                        log.close()
                        log = args.phase_log.open()
                        continue
                    if path_stat.st_size < log.tell():
                        log.seek(0)
                        continue
                    time.sleep(0.2)
                    continue
                diagnostic = re.search(r"(?:^|\] )SHILLING_(RUNTIME_PROFILE|ALLOCATION_MAIN|ALLOCATION_SQL) (\{.*\})$", line)
                if diagnostic:
                    try:
                        result["diagnostics"].append({"probe": diagnostic.group(1),
                            "seconds": round(time.monotonic() - started, 3),
                            "values": json.loads(diagnostic.group(2))})
                    except json.JSONDecodeError:
                        result["diagnostics"].append({"probe": diagnostic.group(1), "error": "Malformed diagnostic JSON"})
                    continue
                marker = re.search(r"(?:^|\] )SHILLING_MEMORY (.+)$", line)
                if not marker:
                    continue
                phase = marker.group(1).strip()
            else:
                phase = input().strip()
            if phase.startswith("BEGIN "):
                result["phases"].append({"name": phase, "seconds": round(time.monotonic() - started, 3)})
                print(phase, flush=True)
                continue
            if phase == "COMPLETE":
                result["complete"] = True
                break
            if phase.startswith("ERROR "):
                result["error"] = phase
                break
            if phase == "quit":
                break
            if not phase:
                continue
            record = {"name": phase, "seconds": round(time.monotonic() - started, 3), **{name: snapshot(pid) for name, pid in processes.items()}}
            result["phases"].append(record)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(record), flush=True)
    except (EOFError, KeyboardInterrupt):
        pass
    finally:
        stop.set()
        thread.join()
        if log:
            log.close()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(f"Saved {args.output}", flush=True)


if __name__ == "__main__":
    main()
