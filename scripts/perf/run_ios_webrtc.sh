#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${IOS_DEVICE_ID:?Set IOS_DEVICE_ID to the physical iPhone UDID}"
IOS_PERF_BUILD_DIR="${IOS_PERF_BUILD_DIR:-/tmp/shilling-perf-ios-build}"
IOS_PERF_SIGNAL_URL="${IOS_PERF_SIGNAL_URL:-ws://$(ipconfig getifaddr en0):8081}"
export KOTLIN_CLI_WRAPPER_PATH="$PWD/kotlin"

# Separate bundle ID and sandbox; the normal Shilling installation is preserved.
xcodebuild -project app/perf-ios/module.xcodeproj -scheme app \
  -configuration Release -destination "id=$IOS_DEVICE_ID" \
  -derivedDataPath "$IOS_PERF_BUILD_DIR" -allowProvisioningUpdates \
  DEVELOPMENT_TEAM="${IOS_TEAM_ID:-7QN6HR273V}" CODE_SIGN_STYLE=Automatic build
xcrun devicectl device install app --device "$IOS_DEVICE_ID" \
  "$IOS_PERF_BUILD_DIR/Build/Products/Release-iphoneos/perf-ios.app"
xcrun devicectl device process launch --device "$IOS_DEVICE_ID" \
  --terminate-existing --console finance.shilling.perf -- \
  --perf-server "$IOS_PERF_SIGNAL_URL" --perf-receiver "${IOS_RECEIVER:-adapter}"
