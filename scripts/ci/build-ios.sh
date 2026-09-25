#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

if [ "$(uname -s)" != Darwin ]; then
    echo "ERROR: Shilling's Kotlin/Native iOS framework requires macOS and Xcode" >&2
    exit 1
fi

command -v xcodebuild >/dev/null
version=$(cat VERSION)
output_dir=mobile-artifacts/ios
archive_path="$PWD/build/ios/Shilling.xcarchive"
rm -rf "$output_dir" "$archive_path"
mkdir -p "$output_dir"

bash setup-webrtc.sh

# The Xcode project calls the Kotlin toolchain in its Build Kotlin phase.
export KOTLIN_CLI_WRAPPER_PATH="$PWD/kotlin"

signing_args=(CODE_SIGNING_ALLOWED=NO)
if [ "${SHILLING_IOS_SIGNED:-0}" = 1 ]; then
    signing_args=(CODE_SIGN_STYLE=Automatic "DEVELOPMENT_TEAM=${IOS_TEAM_ID:-7QN6HR273V}")
fi

xcodebuild archive \
    -project app/ios-app/module.xcodeproj \
    -scheme app \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "$archive_path" \
    "${signing_args[@]}"

app_path="$archive_path/Products/Applications/ios-app.app"
if [ ! -d "$app_path" ]; then
    echo "ERROR: iOS app is missing from Xcode archive" >&2
    exit 1
fi

if [ "${SHILLING_IOS_SIGNED:-0}" != 1 ]; then
    ditto -c -k --sequesterRsrc --keepParent "$app_path" \
        "$output_dir/Shilling_${version}_ios-unsigned-app.zip"
    echo "Unsigned iOS build produced. A signed IPA needs macOS signing credentials."
    exit 0
fi

export_options="$PWD/build/ios/ExportOptions.plist"
cat > "$export_options" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>export</string>
  <key>signingStyle</key><string>automatic</string>
  <key>teamID</key><string>${IOS_TEAM_ID:-7QN6HR273V}</string>
</dict></plist>
EOF

xcodebuild -exportArchive \
    -archivePath "$archive_path" \
    -exportOptionsPlist "$export_options" \
    -exportPath "$PWD/build/ios/export"

ipa=$(find "$PWD/build/ios/export" -maxdepth 1 -name '*.ipa' -type f -print -quit)
if [ -z "$ipa" ] || [ ! -s "$ipa" ]; then
    echo "ERROR: signed iOS IPA was not exported" >&2
    exit 1
fi
cp "$ipa" "$output_dir/Shilling_${version}_ios.ipa"
