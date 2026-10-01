#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

if [ "$(uname -s)" != Darwin ]; then
    echo "ERROR: Shilling's Kotlin/Native iOS framework requires macOS and Xcode" >&2
    exit 1
fi

command -v xcodebuild >/dev/null
signing_args=(CODE_SIGNING_ALLOWED=NO)
apple_auth_args=()
if [ "${SHILLING_IOS_SIGNED:-0}" = 1 ]; then
    : "${IOS_TEAM_ID:?Set the Apple Developer team ID for signed builds}"
    # Use a file on the Mac agent, never the private key contents in an env var
    # (Xcode prints build environment variables in its logs).
    : "${ASC_KEY_PATH:?Set the path to the App Store Connect team API key}"
    : "${ASC_KEY_ID:?Set the App Store Connect key ID}"
    : "${ASC_ISSUER_ID:?Set the App Store Connect issuer ID}"
    test -r "$ASC_KEY_PATH"
    if [ -n "${BUILD_VCS_BRANCH:-}" ] && [ "$BUILD_VCS_BRANCH" != main ]; then
        echo "ERROR: TeamCity signed iOS builds must run on main" >&2
        exit 1
    fi
    if [ -n "${IOS_KEYCHAIN_PATH:-}" ]; then
        : "${IOS_KEYCHAIN_PASSWORD_FILE:?Set the runner-local signing keychain password file}"
        security unlock-keychain -p "$(cat "$IOS_KEYCHAIN_PASSWORD_FILE")" "$IOS_KEYCHAIN_PATH"
    fi
    apple_auth_args=(-allowProvisioningUpdates
        -authenticationKeyPath "$ASC_KEY_PATH"
        -authenticationKeyID "$ASC_KEY_ID"
        -authenticationKeyIssuerID "$ASC_ISSUER_ID")
    signing_args=(CODE_SIGNING_ALLOWED=YES CODE_SIGN_STYLE=Automatic "DEVELOPMENT_TEAM=$IOS_TEAM_ID")
fi
version=$(cat VERSION)
output_dir=mobile-artifacts/ios
archive_path="$PWD/build/ios/Shilling.xcarchive"
rm -rf "$output_dir" "$archive_path"
mkdir -p "$output_dir"

bash setup-webrtc.sh

# The Xcode project calls the Kotlin toolchain in its Build Kotlin phase.
export KOTLIN_CLI_WRAPPER_PATH="$PWD/kotlin"

xcodebuild archive \
    -project app/ios-app/module.xcodeproj \
    -scheme app \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "$archive_path" \
    "${signing_args[@]}" \
    ${apple_auth_args[@]+"${apple_auth_args[@]}"}

app_path="$archive_path/Products/Applications/ios-app.app"
if [ ! -d "$app_path" ]; then
    echo "ERROR: iOS app is missing from Xcode archive" >&2
    exit 1
fi

python3 scripts/ci/check-ios-archive.py "$archive_path" "$version"

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
  <key>teamID</key><string>${IOS_TEAM_ID}</string>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict></plist>
EOF

rm -rf "$PWD/build/ios/export"
xcodebuild -exportArchive \
    -archivePath "$archive_path" \
    -exportOptionsPlist "$export_options" \
    -exportPath "$PWD/build/ios/export" \
    "${apple_auth_args[@]}"

ipa=$(find "$PWD/build/ios/export" -maxdepth 1 -name '*.ipa' -type f -print -quit)
if [ -z "$ipa" ] || [ ! -s "$ipa" ]; then
    echo "ERROR: signed iOS IPA was not exported" >&2
    exit 1
fi
cp "$ipa" "$output_dir/Shilling_${version}_ios.ipa"
