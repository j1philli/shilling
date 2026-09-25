#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

version=$(cat VERSION)
output_dir=mobile-artifacts/android
rm -rf "$output_dir"
mkdir -p "$output_dir"

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

echo "Android packages:"
ls -lh "$output_dir"
