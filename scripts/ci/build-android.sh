#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

version=$(cat VERSION)
output_dir=mobile-artifacts/android
rm -rf "$output_dir"
mkdir -p "$output_dir"

# CI uses its globally increasing build ID; local builds retain the default.
module=app/android-app/module.yaml
backup=$(mktemp)
cp "$module" "$backup"
trap 'cp "$backup" "$module"; rm -f "$backup"' EXIT
if [ -n "${TEAMCITY_BUILD_ID:-}" ]; then
    python3 - <<'PYCODE'
import os, re
from pathlib import Path
code = os.environ['TEAMCITY_BUILD_ID']
if not code.isdigit() or not 1 <= int(code) <= 2100000000:
    raise ValueError('Invalid CI Android version code')
p = Path('app/android-app/module.yaml')
s, count = re.subn(r'(?m)^(\s+versionCode:) \d+\s*$', lambda m: m[1] + ' ' + code, p.read_text())
if count != 1:
    raise ValueError('Expected one Android versionCode')
p.write_text(s + '\n')
PYCODE
fi

bash scripts/ci/retry.sh ./kotlin build -m android-app
bash scripts/ci/retry.sh ./kotlin package -m android-app -f aab

apk=build/tasks/_android-app_buildAndroidDebug/gradle-project-debug.apk
aab=build/tasks/_android-app_bundleAndroid/gradle-project-release.aab

if [ ! -s "$apk" ] || [ ! -s "$aab" ]; then
    echo "ERROR: Android APK or AAB was not produced" >&2
    exit 1
fi

cp "$apk" "$output_dir/Shilling_${version}_android-debug.apk"
cp "$aab" "$output_dir/Shilling_${version}_android-release-unsigned.aab"

if [ -n "${TEAMCITY_BUILD_ID:-}" ]; then
    python3 scripts/ci/android_provenance.py "$output_dir/Shilling_${version}_android-release-unsigned.aab"
fi

echo "Android packages:"
ls -lh "$output_dir"
