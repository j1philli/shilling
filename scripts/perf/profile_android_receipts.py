#!/usr/bin/env python3
"""Profile an installed Release fixture against an already running local signaling server."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)


def records(pid):
    output = adb("logcat", "-d", "-v", "raw", "--pid=" + pid, "-s", "ShillingP2p:I", "*:S")
    return [json.loads(line) for line in output.splitlines() if line.startswith("{")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workload", choices=["send", "upload", "receive"], default="send")
    parser.add_argument("--repeats", type=int, choices=range(1, 6), default=3)
    parser.add_argument("--label", default="current")
    parser.add_argument("--signal-url", default="ws://127.0.0.1:8081/ws/signal")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.workload in ("upload", "receive") and args.repeats != 1:
        parser.error("upload and receive require --repeats 1")
    root = Path(__file__).resolve().parents[2]
    try:
        adb("shell", "am", "start", "-S", "-W", "-n", "finance.shilling.perf/.P2pPerformanceActivity",
            "--ez", "reuseReceipts", "true")
        pid = adb("shell", "pidof", "finance.shilling.perf").strip()
        ready = None
        for _ in range(30):
            ready_rows = [row for row in records(pid) if row.get("status") == "ready"]
            ready = ready_rows[-1] if ready_rows else None
            if ready:
                break
            time.sleep(1)
        if ready is None:
            raise RuntimeError("Fixture did not become ready; seed its synthetic receipts first")
        env = os.environ.copy()
        for name in ["RAW", "COMPARE", "UPLOAD_ONLY", "UPLOAD_NO_READBACK", "IDLE_ONLY", "RECONNECTS", "SLOW_ONLY", "CAPTURE",
                     "SOCKET_DIAG", "ROUTE_FILE", "MEDIA_BUFFER_PROBE", "RECEIVE_BUFFER_PROBE_BYTES",
                     "CONNECT_SETTLE_MS", "PEER_ID"]:
            env.pop(name, None)
        env.update(SIGNAL_URL=args.signal_url, FAST_ONLY="1", REPEATS=str(args.repeats), SIZES="50")
        if args.workload in ("upload", "receive"):
            env["UPLOAD_ONLY"] = "50"
        if args.workload == "receive":
            env["UPLOAD_NO_READBACK"] = "1"
        run = subprocess.run(["node", "scripts/perf/bench_live_webrtc.cjs"], cwd=root, env=env,
                             text=True, capture_output=True, timeout=240)
        if run.returncode:
            raise RuntimeError(run.stderr.strip() or "Browser receipt workload failed")
        browser = [json.loads(line) for line in run.stdout.splitlines() if line.startswith("{")]
        time.sleep(2)  # Read the lifetime high-water counter after the send finishes.
        rows = records(pid)
        rows = rows[max(i for i, row in enumerate(rows) if row.get("status") == "ready"):]
        if args.workload == "receive" and not any(row.get("stage") == "upload_stored" for row in rows):
            raise RuntimeError("Native Store5 receipt commit was not observed")
        memory = [row["memory"] for row in rows if "memory" in row]
        result = {
            "label": args.label, "workload": args.workload, "repeats": args.repeats,
            "readyMemory": ready["memory"],
            "peakVmHwmKiB": max(row["vmHwmKiB"] for row in memory),
            "maxSampledPssKiB": max(row["pssKiB"] for row in memory),
            "maxSampledJavaHeapBytes": max(row["javaHeapBytes"] for row in memory),
            "uploadStored": [row["memory"] for row in rows if row.get("stage") == "upload_stored"],
            "sendEnds": [row for row in rows if row.get("pipeline")],
            "browser": browser,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({"output": str(args.output), "peakVmHwmKiB": result["peakVmHwmKiB"],
                          "browser": browser}))
    finally:
        adb("shell", "am", "force-stop", "finance.shilling.perf")
        adb("shell", "am", "start", "-W", "-n", "finance.shilling.android/.MainActivity")


if __name__ == "__main__":
    main()
