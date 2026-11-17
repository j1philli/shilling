#!/usr/bin/env bash
# Measure the optimized app; debug DEX verification is not a production baseline.
set -euo pipefail
cd "$(dirname "$0")/../.."
./kotlin task :android-app:buildAndroidRelease
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
apksigner=$(find "$sdk/build-tools" -name apksigner -type f | sort | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
"$apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --out "$work/shilling.apk" build/tasks/_android-app_buildAndroidRelease/gradle-project-release-unsigned.apk
# Replace the locally signed app in place. Never uninstall or clear its data.
adb install -r "$work/shilling.apk"
python3 - <<'PY'
import json, re, statistics, subprocess
samples = []
for _ in range(5):
    output = subprocess.check_output(['adb', 'shell', 'am', 'start', '-S', '-W', '-n', 'finance.shilling.android/.MainActivity'], text=True)
    match = re.search(r'TotalTime: (\d+)', output)
    if 'Status: ok' not in output or 'LaunchState: COLD' not in output or not match:
        raise SystemExit('Invalid cold launch sample: ' + output)
    samples.append(int(match[1]))
print(json.dumps({'coldLaunchMs': samples, 'medianMs': statistics.median(samples)}))
print(subprocess.check_output(['adb', 'shell', 'dumpsys', 'gfxinfo', 'finance.shilling.android'], text=True))
PY
