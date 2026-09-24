#!/usr/bin/env bash
# Verifies a macOS CI agent has all required tools for Shilling builds.
# Exit non-zero with clear messages for any missing/incompatible tool.
set -euo pipefail

fail=0

check() {
  local name="$1"
  local cmd="$2"

  if ! command -v "$cmd" &>/dev/null; then
    echo "FAIL: $name — '$cmd' not found"
    fail=1
    return
  fi
  echo "  OK: $name — $(command -v "$cmd")"
}

check_java() {
  if ! command -v java &>/dev/null; then
    echo "FAIL: JDK 21+ — 'java' not found"
    fail=1
    return
  fi
  local ver
  ver=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
  if [ "$ver" -lt 21 ]; then
    echo "FAIL: JDK 21+ — found JDK $ver"
    fail=1
  else
    echo "  OK: JDK — version $ver"
  fi
}

check_node() {
  if ! command -v node &>/dev/null; then
    echo "FAIL: Node.js 18+ — 'node' not found"
    fail=1
    return
  fi
  local ver
  ver=$(node --version | sed 's/v\([0-9]*\).*/\1/')
  if [ "$ver" -lt 18 ]; then
    echo "FAIL: Node.js 18+ — found Node $ver"
    fail=1
  else
    echo "  OK: Node.js — $(node --version)"
  fi
}

check_xcode() {
  if ! command -v xcodebuild &>/dev/null; then
    echo "FAIL: Xcode — 'xcodebuild' not found"
    fail=1
    return
  fi
  local ver
  ver=$(xcodebuild -version | head -1 | sed 's/Xcode //')
  local major
  major=$(echo "$ver" | cut -d. -f1)
  if [ "$major" -lt 15 ]; then
    echo "FAIL: Xcode 15+ — found Xcode $ver"
    fail=1
  else
    echo "  OK: Xcode — $ver"
  fi
}

check_signing() {
  if ! security find-identity -v -p codesigning 2>/dev/null | grep -q "valid identities found" || \
     security find-identity -v -p codesigning 2>/dev/null | grep -q "1)"; then
    echo "  OK: Code signing — identity found in keychain"
  else
    echo "WARN: Code signing — no valid signing identity found in keychain"
  fi
}

check_kotlin_cli() {
  local root="$1"
  if [ ! -x "$root/kotlin" ]; then
    echo "FAIL: Kotlin CLI — ./kotlin script not found or not executable"
    fail=1
  else
    echo "  OK: Kotlin CLI — $root/kotlin"
  fi
}

echo "=== Shilling macOS Agent Health Check ==="
echo ""

check_java
check_xcode
check_node
check "npm"           npm
check "Rust/cargo"    cargo
check "cargo-tauri"   cargo-tauri
check "ImageMagick"   magick
check "gh CLI"        gh

echo ""
echo "--- Signing ---"
check_signing

echo ""
echo "--- Project ---"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
check_kotlin_cli "$ROOT_DIR"

echo ""
if [ "$fail" -ne 0 ]; then
  echo "RESULT: Some checks failed. Install missing tools before running CI."
  exit 1
fi
echo "RESULT: All checks passed."
