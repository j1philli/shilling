#!/usr/bin/env python3
"""Measure the installed isolated Release receipt-list fixture; restore normal Shilling afterward."""
import argparse
import json
from pathlib import Path
import statistics
import subprocess
import time


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--label", default="current")
    parser.add_argument("--variant", choices=("joined", "combined"), default="joined")
    parser.add_argument("--receipts", type=int, choices=(250, 1000, 10000), default=250)
    parser.add_argument("--processes", type=int, choices=range(1, 6), default=3)
    args = parser.parse_args()
    runs = []
    try:
        for process in range(args.processes):
            adb("shell", "am", "start", "-S", "-W", "-n", "finance.shilling.perf/.ReceiptListPerformanceActivity",
                "--es", "variant", args.variant, "--ei", "receiptCount", str(args.receipts))
            pid = adb("shell", "pidof", "finance.shilling.perf").strip()
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                log = adb("logcat", "-d", "-v", "raw", "--pid=" + pid, "-s", "ShillingList:I", "*:S")
                rows = [json.loads(line) for line in log.splitlines() if line.startswith("{")]
                failed = next((r for r in rows if r.get("status") == "failed"), None)
                if failed:
                    raise RuntimeError(failed)
                if any(r.get("status") == "complete" for r in rows):
                    metrics = [r for r in rows if "workload" in r]
                    if len(metrics) != 20:
                        raise RuntimeError("Incomplete workload metrics")
                    runs.append({"process": process, "metrics": metrics})
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("Receipt-list fixture timed out")
        summary = {}
        for name in ("initial", "posting_edit", "schedule_edit", "receipt_edit"):
            metrics = [r for run in runs for r in run["metrics"] if r["workload"] == name]
            summary[name] = {
                "samples": len(metrics),
                "medianMs": round(statistics.median(r["milliseconds"] for r in metrics), 3),
                "medianSelectQueries": statistics.median(r["selectQueries"] for r in metrics),
                "medianRowsRead": statistics.median(r["rowsRead"] for r in metrics),
                "medianSqlQueryMs": round(statistics.median(r["sqlQueryMs"] for r in metrics), 3),
            }
        result = {"label": args.label, "variant": args.variant, "postings": 10000, "schedules": 1000, "receipts": args.receipts,
                  "runs": runs, "summary": summary}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({"label": args.label, "summary": summary}))
    finally:
        adb("shell", "am", "force-stop", "finance.shilling.perf")
        adb("shell", "am", "start", "-W", "-n", "finance.shilling.android/.MainActivity")


if __name__ == "__main__":
    main()
