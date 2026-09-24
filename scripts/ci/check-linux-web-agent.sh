#!/usr/bin/env bash
# Verifies a Linux CI agent has the tools required for the web build/deploy job.
set -euo pipefail

if [ -f /etc/profile.d/shilling-ci-agent.sh ]; then
  # shellcheck disable=SC1091
  source /etc/profile.d/shilling-ci-agent.sh
fi

fail=0

check_command() {
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

check_kotlin_cli() {
  local root="$1"
  if [ ! -x "$root/kotlin" ]; then
    echo "FAIL: Kotlin CLI — ./kotlin script not found or not executable"
    fail=1
  else
    echo "  OK: Kotlin CLI — $root/kotlin"
  fi
}

check_wrangler() {
  local wrangler_version

  if command -v wrangler &>/dev/null; then
    if ! wrangler_version=$(wrangler --version 2>/dev/null); then
      echo "FAIL: Wrangler — 'wrangler --version' failed"
      fail=1
    else
      echo "  OK: Wrangler — $wrangler_version"
    fi
    return
  fi

  if ! wrangler_version=$(npx --yes wrangler --version 2>/dev/null); then
    echo "FAIL: Wrangler — neither 'wrangler' nor 'npx --yes wrangler --version' worked"
    fail=1
  else
    echo "  OK: Wrangler — $wrangler_version"
  fi
}

echo "=== Shilling Linux Web Agent Health Check ==="
echo ""

check_java
check_node
check_command "npm" npm
check_command "unzip" unzip
check_command "just" just
check_wrangler

echo ""
echo "--- Project ---"
SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -x "$SCRIPT_ROOT/kotlin" ]; then
  check_kotlin_cli "$SCRIPT_ROOT"
elif [ -n "${TEAMCITY_BUILD_CHECKOUTDIR:-}" ]; then
  check_kotlin_cli "$TEAMCITY_BUILD_CHECKOUTDIR"
else
  check_kotlin_cli "$PWD"
fi

echo ""
if [ "$fail" -ne 0 ]; then
  echo "RESULT: Some checks failed. Install missing tools before running the web CI job."
  exit 1
fi
echo "RESULT: All checks passed."
