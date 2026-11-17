#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

version=$(cat VERSION)
dmg="$PWD/desktop-artifacts/macos/Shilling_${version}_universal.dmg"
test -s "$dmg"
output="$PWD/smoke-results/macos"
mkdir -p "$output"
temporary=$(mktemp -d /tmp/shilling-smoke.XXXXXX)
mountpoint="$temporary/mount"
mkdir "$mountpoint"
pid=''
cleanup() {
    if [ -n "$pid" ]; then kill "$pid" >/dev/null 2>&1 || true; wait "$pid" 2>/dev/null || true; fi
    hdiutil detach -quiet "$mountpoint" >/dev/null 2>&1 || true
    rm -rf "$temporary"
}
trap cleanup EXIT

hdiutil verify "$dmg"
hdiutil attach -quiet -readonly -nobrowse -mountpoint "$mountpoint" "$dmg"
ditto "$mountpoint/Shilling.app" "$temporary/Shilling.app"
hdiutil detach -quiet "$mountpoint"
binary="$temporary/Shilling.app/Contents/MacOS/shilling"
architectures=$(lipo -archs "$binary")
[[ " $architectures " == *' arm64 '* && " $architectures " == *' x86_64 '* ]]
echo "Verified universal DMG ($architectures)"

# Launch the extracted package in the CI user's GUI session. This verifies
# native startup, not rendering, sign-in, or Gatekeeper acceptance.
open -g -n "$temporary/Shilling.app"
sleep 2
pid=$(pgrep -f "$temporary/Shilling.app/Contents/MacOS/shilling" | head -1 || true)
if [ -z "$pid" ]; then echo 'Shilling exited during startup' >&2; exit 1; fi
sleep 8
kill -0 "$pid"
printf 'DMG checksum, extraction, arm64/x86_64 slices, and 10-second launch passed.\n' | tee "$output/result.txt"
