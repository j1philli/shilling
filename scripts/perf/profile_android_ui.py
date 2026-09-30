#!/usr/bin/env python3
"""Profile populated shared Compose screens in the isolated Pixel fixture.

Run after installing the Release perf APK. Prints synthetic, per-run JSON.
The fixture owns its database; this script only launches the normal app afterward.
"""

import argparse
import json
import re
import subprocess
import time


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)


def events(pid):
    output = adb("logcat", "-d", "-v", "raw", f"--pid={pid}", "-s", "ShillingUi:I", "*:S")
    return [json.loads(line) for line in output.splitlines() if line.startswith("{")]


def gfxinfo():
    output = adb("shell", "dumpsys", "gfxinfo", "finance.shilling.perf")
    def value(label):
        found = re.search(rf"^{re.escape(label)}:\s*(\d+)", output, re.MULTILINE)
        if not found:
            raise RuntimeError(f"Missing gfxinfo metric: {label}")
        return int(found.group(1))
    return {"frames": value("Total frames rendered"), "jankyFrames": value("Janky frames"),
            "p95Ms": value("95th percentile"), "p99Ms": value("99th percentile")}


def meminfo():
    output = adb("shell", "dumpsys", "meminfo", "finance.shilling.perf")
    match = re.search(r"TOTAL PSS:\s*(\d+).*TOTAL RSS:\s*(\d+)", output)
    if not match:
        raise RuntimeError("Missing meminfo totals")
    return {"pssKiB": int(match.group(1)), "rssKiB": int(match.group(2))}


def verify(screen):
    adb("shell", "uiautomator", "dump", "/sdcard/shilling-perf-window.xml")
    xml = adb("shell", "cat", "/sdcard/shilling-perf-window.xml")
    marker = "Synthetic transaction" if screen == "activity" else "Synthetic schedule"
    if marker not in xml:
        raise RuntimeError(f"Populated {screen} screen not visible")


def measure(screen, run, scroll):
    adb("shell", "am", "start", "-S", "-W", "-n",
        "finance.shilling.perf/.UiPerformanceActivity", "--es", "screen", screen)
    pid = adb("shell", "pidof", "finance.shilling.perf").strip()
    deadline = time.monotonic() + 90
    rows = []
    while time.monotonic() < deadline:
        rows = events(pid)
        if any(row.get("status") == "contentReady" for row in rows) and sum(
                row.get("status") == "initialFrame" for row in rows) >= 3:
            break
        time.sleep(0.4)
    else:
        raise RuntimeError(f"{screen} did not show populated content and three frames within 90s")
    verify(screen)
    baseline = {
        "screen": screen, "run": run,
        "fixture": next(row for row in rows if row.get("status") == "fixtureReady"),
        "contentReady": next(row for row in rows if row.get("status") == "contentReady"),
        "initialFrames": [row for row in rows if row.get("status") == "initialFrame"],
        "coldGfxinfo": gfxinfo(), "steadyMemory": meminfo(),
        "populatedScreenVerified": True,
    }
    if scroll:
        adb("shell", "dumpsys", "gfxinfo", "finance.shilling.perf", "reset")
        adb("shell", "for i in 1 2 3 4 5 6 7 8 9 10; do input swipe 550 1800 550 800 350; done")
        baseline["scrollGfxinfo"] = gfxinfo()
    return baseline


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--scroll", action="store_true")
    args = parser.parse_args()
    if args.runs < 1 or args.runs > 5:
        parser.error("--runs must be 1–5")
    try:
        for run in range(1, args.runs + 1):
            for screen in ("activity", "plan"):
                print(json.dumps(measure(screen, run, args.scroll)), flush=True)
    finally:
        subprocess.run(["adb", "shell", "am", "force-stop", "finance.shilling.perf"], check=False)
        subprocess.run(["adb", "shell", "rm", "-f", "/sdcard/shilling-perf-window.xml"], check=False)
        subprocess.run(["adb", "shell", "am", "start", "-n",
                        "finance.shilling.android/finance.shilling.android.MainActivity"],
                       check=False, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
