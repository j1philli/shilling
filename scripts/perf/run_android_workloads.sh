#!/usr/bin/env bash
# Builds an optimized, isolated benchmark APK; never installs over Shilling.
set -euo pipefail
cd "$(dirname "$0")/../.."
./kotlin task :perf-android:buildAndroidRelease
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
apksigner=$(find "$sdk/build-tools" -name apksigner -type f | sort | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# This is Android's standard local debug signing key, not a release credential.
"$apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --out "$work/perf.apk" build/tasks/_perf-android_buildAndroidRelease/gradle-project-release-unsigned.apk
adb install -r "$work/perf.apk"
adb shell am start -S -W -n finance.shilling.perf/.PerformanceActivity
python3 - <<'PY'
import json, subprocess, sys, time
pid = subprocess.check_output(['adb', 'shell', 'pidof', 'finance.shilling.perf'], text=True).strip()
deadline = time.monotonic() + 180
seen = 0
while time.monotonic() < deadline:
    output = subprocess.check_output(['adb', 'logcat', '-d', '-v', 'raw', '--pid=' + pid, '-s', 'ShillingPerf:I', '*:S'], text=True)
    rows = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
    for result in rows[seen:]:
        print(json.dumps(result), flush=True)
        if 'status' in result:
            sys.exit(0 if result['status'] == 'complete' else 1)
    seen = len(rows)
    time.sleep(1)
raise SystemExit('Timed out waiting for benchmark results')
PY
