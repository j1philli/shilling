#!/usr/bin/env bash
set -euo pipefail

if [ -f /etc/profile.d/shilling-ci-agent.sh ]; then
    # shellcheck disable=SC1091
    source /etc/profile.d/shilling-ci-agent.sh
fi

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/shilling-ci/android-sdk}}
export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"

if [ -d "$sdk_root/platforms/android-37.0" ]; then
    echo "Android SDK 37.0 is installed"
    exit 0
fi

sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$sdkmanager" ]; then
    echo "ERROR: Android sdkmanager is missing at $sdkmanager" >&2
    exit 1
fi

yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null 2>&1 || true
"$sdkmanager" --sdk_root="$sdk_root" "platforms;android-37.0"

test -d "$sdk_root/platforms/android-37.0"
