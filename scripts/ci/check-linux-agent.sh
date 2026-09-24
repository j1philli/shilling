#!/usr/bin/env bash
# Verifies a Linux CI agent has all required tools for Shilling builds.
# Exit non-zero with clear messages for any missing/incompatible tool.
set -euo pipefail

if [ -f /etc/profile.d/shilling-ci-agent.sh ]; then
  # shellcheck disable=SC1091
  source /etc/profile.d/shilling-ci-agent.sh
fi

fail=0

check() {
  local name="$1"
  local cmd="$2"
  local min_version="${3:-}"
  local actual_version

  if ! command -v "$cmd" &>/dev/null; then
    echo "FAIL: $name — '$cmd' not found"
    fail=1
    return
  fi

  if [ -n "$min_version" ]; then
    actual_version=$("${@:4}") || true
    echo "  OK: $name — $actual_version (need >= $min_version)"
  else
    echo "  OK: $name — $(command -v "$cmd")"
  fi
}

check_java() {
  if ! command -v java &>/dev/null; then
    echo "FAIL: JDK 21+ — 'java' not found"
    fail=1
    return
  fi
  local ver
  ver=$(java -version 2>&1 | awk -F'"' '/version/ { split($2, parts, "."); print parts[1]; exit }')
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

check_android_sdk() {
  local managed_sdk="/opt/shilling-ci/android-sdk"
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

  if [ -n "$sdk_root" ] && [ ! -d "$sdk_root/platforms/android-36" ] && [ -d "$managed_sdk/platforms/android-36" ]; then
    sdk_root="$managed_sdk"
    export ANDROID_HOME="$managed_sdk"
    export ANDROID_SDK_ROOT="$managed_sdk"
  fi

  if [ -z "$sdk_root" ]; then
    echo "FAIL: Android SDK — neither ANDROID_HOME nor ANDROID_SDK_ROOT is set"
    fail=1
    return
  fi

  # Check compileSdk 36
  if [ ! -d "$sdk_root/platforms/android-36" ]; then
    echo "FAIL: Android SDK — platforms/android-36 not found in $sdk_root"
    fail=1
  else
    echo "  OK: Android SDK — android-36 platform found"
  fi

  # Check build-tools (any version)
  if ! ls "$sdk_root/build-tools"/ &>/dev/null; then
    echo "FAIL: Android SDK — no build-tools found in $sdk_root"
    fail=1
  else
    local bt
    bt=$(ls "$sdk_root/build-tools/" | sort -V | tail -1)
    echo "  OK: Android SDK — build-tools $bt"
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

echo "=== Shilling Linux Agent Health Check ==="
echo ""

check_java
check_node
check "npm"         npm
check "Rust/cargo"  cargo   "" cargo --version
check "ripgrep"     rg      "" rg --version
check "gh CLI"      gh      "" gh --version
check "wrangler"    wrangler "" wrangler --version
check "Docker"      docker  "" docker --version

echo ""
echo "--- Android SDK ---"
check_android_sdk

echo ""
echo "--- Project ---"
SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -x "$SCRIPT_ROOT/kotlin" ]; then
  check_kotlin_cli "$SCRIPT_ROOT"
elif [ -n "${TEAMCITY_BUILD_CHECKOUTDIR:-}" ]; then
  check_kotlin_cli "$TEAMCITY_BUILD_CHECKOUTDIR"
elif [ -x "$PWD/kotlin" ]; then
  check_kotlin_cli "$PWD"
else
  echo "  OK: Project checkout — skipped outside a Shilling checkout"
fi

echo ""
if [ "$fail" -ne 0 ]; then
  echo "RESULT: Some checks failed. Install missing tools before running CI."
  exit 1
fi
echo "RESULT: All checks passed."
