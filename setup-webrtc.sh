#!/usr/bin/env bash
set -euo pipefail

# Downloads the WebRTC.xcframework required by ktor-client-webrtc on iOS.
# The framework is gitignored (app/ios-app/Frameworks/) so each clone must fetch it.
# Version should match the WebRTC-SDK version expected by the ktor-client-webrtc
# dependency declared in libs.versions.toml.

WEBRTC_VERSION="${WEBRTC_SDK_VERSION:-137.7151.04}"
FRAMEWORK_DIR="$(cd "$(dirname "$0")" && pwd)/app/ios-app/Frameworks"
XCFW_DIR="${FRAMEWORK_DIR}/WebRTC.xcframework"
MARKER="${XCFW_DIR}/.version"

# Skip if already downloaded at the correct version
if [ -f "${MARKER}" ] && [ "$(cat "${MARKER}")" = "${WEBRTC_VERSION}" ]; then
    echo "WebRTC.xcframework ${WEBRTC_VERSION} already present, skipping download."
    exit 0
fi

echo "Downloading WebRTC.xcframework ${WEBRTC_VERSION}..."

DOWNLOAD_URL="https://github.com/webrtc-sdk/Specs/releases/download/${WEBRTC_VERSION}/WebRTC.xcframework.zip"
TMP_ZIP=$(mktemp /tmp/webrtc-xcframework-XXXXXX.zip)

cleanup() { rm -f "${TMP_ZIP}"; }
trap cleanup EXIT

curl -fSL --retry 3 -o "${TMP_ZIP}" "${DOWNLOAD_URL}"

# Remove old framework if present
rm -rf "${XCFW_DIR}"
mkdir -p "${FRAMEWORK_DIR}"

echo "Extracting to ${XCFW_DIR}..."
unzip -q "${TMP_ZIP}" -d "${FRAMEWORK_DIR}"

# The zip may contain WebRTC.xcframework/ at the top level or nested.
# Ensure it ends up at the expected path.
if [ ! -d "${XCFW_DIR}" ]; then
    # Check if it extracted with a wrapper directory
    EXTRACTED=$(find "${FRAMEWORK_DIR}" -maxdepth 2 -name "WebRTC.xcframework" -type d | head -1)
    if [ -n "${EXTRACTED}" ]; then
        mv "${EXTRACTED}" "${XCFW_DIR}"
    else
        echo "error: WebRTC.xcframework not found after extraction"
        exit 1
    fi
fi

# Write version marker for idempotency
echo "${WEBRTC_VERSION}" > "${MARKER}"

echo "WebRTC.xcframework ${WEBRTC_VERSION} ready at ${XCFW_DIR}"
