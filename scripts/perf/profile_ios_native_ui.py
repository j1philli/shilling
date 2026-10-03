#!/usr/bin/env python3
"""Run an installed Release Shilling Perf app on a physical iPhone with synthetic UI data.

The fixture compiles the production SwiftUI sources via symlinks. It uses its own
Store5 database; only hosted-settings connects to a synthetic metadata server.
This script never launches the normal app. Closing its console also ends the
fixture process after measurement.
"""
import argparse
import json
import os
import pathlib
import selectors
import subprocess
import tempfile
import time
import uuid


def validate_editor_lifetimes(records):
    closed = [r for r in records if r["event"] == "editor-closed"]
    if len(closed) != 12 or any(not r["controllerReleased"] or r["database"]["activeListeners"] for r in closed):
        raise RuntimeError("Editor controllers or database listeners remained after closing")
    measured = [r for r in records if r["event"] == "measurement"]
    rebuilds = [r for r in measured if r["phase"].startswith("rebuild-")]
    after_close = [r for r in measured if r["phase"] == "after-close-updates"]
    if len(rebuilds) != 12 or len(after_close) != 1 or any(r["database"]["queries"] for r in rebuilds + after_close):
        raise RuntimeError("Editor parent rebuilds or closed editors caused extra database reads")


def validate_editor_navigation(records):
    closed = [r for r in records if r["event"] == "navigation-closed"]
    if len(closed) != 8 or any(r["database"]["activeListeners"] for r in closed):
        raise RuntimeError("Navigation left editor database listeners active")
    after_close = [r for r in records if r["event"] == "measurement" and r["phase"] == "after-close-updates"]
    if len(after_close) != 1 or after_close[0]["database"]["queries"]:
        raise RuntimeError("Closed navigation editors caused database reads")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--screen", choices=["activity", "receipts", "plan", "plan-transitions", "hosted-settings", "home", "editors", "editor-navigation", "editor-choices", "editor-save", "tabs", "receipt-previews"], required=True)
    parser.add_argument("--hosted-server", help="Local synthetic control-plane fixture URL, required for hosted-settings")
    parser.add_argument("--runs", type=int, default=3, choices=range(1, 6))
    parser.add_argument("--label", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--check-navigation", action="store_true", help="Also verify Plan section switching after measurement")
    parser.add_argument("--baseline-receipts", action="store_true", help="Reproduce the former synchronous picker and retained preview copies")
    args = parser.parse_args()
    if args.screen == "hosted-settings" and not args.hosted_server:
        parser.error("--hosted-server is required for hosted-settings")
    if args.baseline_receipts and args.screen != "receipt-previews":
        parser.error("--baseline-receipts requires --screen receipt-previews")
    records = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for index in range(args.runs):
        run = uuid.uuid4().hex
        command = ["xcrun", "devicectl", "device", "process", "launch", "--device", args.device,
                   "--terminate-existing", "--console", "finance.shilling.perf", "--",
                   "--perf-ui", "--ui-screen", args.screen, "--ui-run", run, "--ui-hold"]
        if args.check_navigation:
            command.append("--ui-check-navigation")
        if args.baseline_receipts:
            command.append("--ui-preview-baseline")
        if args.hosted_server:
            command.extend(["--ui-hosted-server", args.hosted_server])
        with tempfile.TemporaryFile(mode="w+") as raw:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0)
            selector = selectors.DefaultSelector()
            selector.register(process.stdout, selectors.EVENT_READ)
            deadline = time.monotonic() + 180
            complete = False
            pending = b""
            try:
                while time.monotonic() < deadline:
                    if not selector.select(timeout=1):
                        if process.poll() is not None:
                            break
                        continue
                    chunk = os.read(process.stdout.fileno(), 65536)
                    if not chunk:
                        break
                    pending += chunk
                    lines = pending.split(b"\n")
                    pending = lines.pop()
                    for encoded in lines:
                        line = encoded.decode(errors="replace")
                        raw.write(line + "\n")
                        marker = "ShillingNativeUI "
                        if marker not in line:
                            continue
                        record = json.loads(line.split(marker, 1)[1])
                        if record.get("run") != run:
                            continue
                        record.update(label=args.label, processRun=index + 1)
                        records.append(record)
                        if record["event"] == "measurement":
                            print(json.dumps(record), flush=True)
                        if record["event"] == "failed":
                            raise RuntimeError(record["reason"])
                        if record["event"] == "complete":
                            complete = True
                    if complete:
                        break
                if not complete:
                    raw.seek(0)
                    raise RuntimeError("Fixture failed or timed out: " + raw.read()[-2000:])
                if args.screen == "editors":
                    validate_editor_lifetimes([r for r in records if r["run"] == run])
                if args.screen == "editor-navigation":
                    validate_editor_navigation([r for r in records if r["run"] == run])
                if args.screen == "editor-choices":
                    checked = [r for r in records if r["run"] == run and r["event"] == "choices-checked"]
                    if len(checked) != 2 or any(r.get("keyboardDoneChecks") != 2 or not r.get("directFocusSwitchChecked") or r["database"]["activeListeners"] for r in checked):
                        raise RuntimeError("Editor choice/keyboard checks did not complete or left database listeners active")
                if args.screen == "editor-save":
                    checked = [r for r in records if r["run"] == run and r["event"] == "editor-save-checked"]
                    if len(checked) != 4 or any(not r["restored"] or r["saveDelayMs"] >= 120 for r in checked):
                        raise RuntimeError("Rapid editor save/restore checks did not complete")
                if args.screen == "hosted-settings":
                    measured = [r for r in records if r["run"] == run and r["event"] == "measurement"]
                    if ([r["phase"] for r in measured] != ["visible", "hidden", "reopened"]
                            or any(r["database"]["mainThreadQueries"] for r in measured)):
                        raise RuntimeError("Hosted Settings phases did not complete or queried the database on Main")
                if args.screen == "plan-transitions":
                    current = [r for r in records if r["run"] == run]
                    measured = [r for r in current if r["event"] == "measurement"]
                    closed = [r for r in current if r["event"] == "plan-closed"]
                    sections = {"Schedules", "Categories", "Accounts", "Overview", "By category", "By day"}
                    expected = {(section, cycle) for section in sections for cycle in (0, 1)}
                    if (len(measured) != 12 or {(r["phase"], r["cycle"]) for r in measured} != expected
                            or len(closed) != 1 or closed[0]["database"]["activeListeners"]
                            or any(r["database"]["mainThreadQueries"] or
                                   (r["cycle"] == 1 and r["database"]["queries"]) for r in measured)):
                        raise RuntimeError("Plan transitions did not complete or left database listeners active")
                if args.screen == "tabs":
                    tab_records = [r for r in records if r["run"] == run]
                    cycles = [r for r in tab_records if r["event"] == "tab-cycle"]
                    if len(cycles) != 6 or any(r["tabCount"] != 5 for r in cycles):
                        raise RuntimeError("Not all five native tabs were exercised")
                    closed = [r for r in tab_records if r["event"] == "tabs-closed"]
                    if len(closed) != 1 or not closed[0]["controllerReleased"] or closed[0]["database"]["activeListeners"]:
                        raise RuntimeError("Tab container or database listeners remained after closing")
                    updates = [r for r in tab_records if r["event"] == "measurement" and r["phase"] == "after-close-updates"]
                    if len(updates) != 1 or updates[0]["database"]["queries"]:
                        raise RuntimeError("Closed tabs caused database reads")
                if args.screen == "receipt-previews":
                    closed = [r for r in records if r["run"] == run and r["event"] == "preview-closed"]
                    if len(closed) != 6 or any(not r["controllerReleased"] for r in closed):
                        raise RuntimeError("Receipt preview controllers remained after closing")
                    if not args.baseline_receipts and any(r["temporaryPreviewBytes"] for r in closed):
                        raise RuntimeError("Receipt preview temporary copies remained after closing")
            finally:
                selector.close()
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                args.output.write_text(json.dumps(records, indent=2) + "\n")
        # Fresh process each time; keep the seeded fixture database.
        if index + 1 < args.runs:
            time.sleep(3)


if __name__ == "__main__":
    main()
