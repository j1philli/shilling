#!/usr/bin/env bash
# Verifies a Linux CI agent has the tools required for the web build/deploy job.
set -euo pipefail

if [ -f /etc/profile.d/shilling-ci-agent.sh ]; then
  # shellcheck disable=SC1091
  source /etc/profile.d/shilling-ci-agent.sh
fi

# Prefer the side-by-side security toolchain without replacing system tools.
managed_tools="$HOME/.local/share/shilling"
if [ -x "$managed_tools/jdk-25.0.5.0.2/bin/java" ]; then
  export JAVA_HOME="$managed_tools/jdk-25.0.5.0.2"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
case "$(uname -s)/$(uname -m)" in
  Linux/x86_64) managed_node="$managed_tools/node-v22.23.4-linux-x64" ;;
  *) managed_node='' ;;
esac
if [ -n "$managed_node" ] && [ -x "$managed_node/bin/node" ]; then
  export PATH="$managed_node/bin:$PATH"
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
  if ! node "$(dirname "${BASH_SOURCE[0]}")/check-java-version.cjs"; then
    fail=1
  fi
}

check_node() {
  if ! command -v node &>/dev/null; then
    echo "FAIL: Patched Node.js (see .nvmrc) — 'node' not found"
    fail=1
    return
  fi

  if ! node "$(dirname "${BASH_SOURCE[0]}")/check-node-version.cjs"; then
    fail=1
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
