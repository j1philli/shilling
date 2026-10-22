#!/usr/bin/env bash
# Install the pinned October 21 security release without changing system Java.
set -euo pipefail
version=25.0.5.0.2
install_dir="${1:-$HOME/.local/share/shilling/jdk-$version}"
if [ -f "$install_dir/release" ] && grep -q "JAVA_VERSION=\"$version\"" "$install_dir/release"; then
    printf '%s\n' "$install_dir"
    exit 0
fi
if [ -e "$install_dir" ]; then
    echo "Refusing to replace an existing Java directory: $install_dir" >&2
    exit 1
fi
case "$(uname -s)/$(uname -m)" in
    Darwin/arm64) platform=macos-aarch64; checksum=09fdf1beb1d87e7a5bb2079bf69248f39156ab034dbfa7062670e04ef5e51ece; strip=3 ;;
    Darwin/x86_64) platform=macos-x64; checksum=7170fef7dd0bb101266188e2e8f27aed8888a628fd05a86a54616a90b0fe95a0; strip=3 ;;
    Linux/aarch64|Linux/arm64) platform=linux-aarch64; checksum=070ae2ee07120a5b0dafac4d40f68eec356f2c1707fba940676f0b358ca269c2; strip=1 ;;
    Linux/x86_64) platform=linux-x64; checksum=d430d3f9e91435d2d58d366b1dabf14b23fcf8629ed4b041757c1be444055946; strip=1 ;;
    *) echo "Unsupported platform for the pinned Java runtime" >&2; exit 1 ;;
esac
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
curl --fail --location --retry 3 --silent --show-error "https://download.oracle.com/java/25/archive/jdk-${version}_${platform}_bin.tar.gz" -o "$scratch/java.tar.gz"
if command -v sha256sum >/dev/null; then
    actual=$(sha256sum "$scratch/java.tar.gz" | cut -d ' ' -f1)
else
    actual=$(shasum -a 256 "$scratch/java.tar.gz" | cut -d ' ' -f1)
fi
[ "$actual" = "$checksum" ] || { echo "Java archive checksum mismatch" >&2; exit 1; }
mkdir "$scratch/java"
tar -xzf "$scratch/java.tar.gz" --strip-components="$strip" -C "$scratch/java"
"$scratch/java/bin/java" -version >&2
mkdir -p "$(dirname "$install_dir")"
mv "$scratch/java" "$install_dir"
printf '%s\n' "$install_dir"
