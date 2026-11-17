#!/usr/bin/env bash
# Workaround: Kotlin Toolchain cache state around the SQLDelight plugin can become
# inconsistent. Clear incremental state, then warm the plugin compile task. If
# warmup fails, fall back to a full clean once.
set -euo pipefail
shopt -s nullglob
cd "$(dirname "$0")/.."

stale=(
  build/tasks/_sqldelight_*
  build/incremental.state/_sqldelight_*
  build/incremental.state/_shared_generate_sqldelight*
  build/incremental.state/_shared_compile*
)
if [ ${#stale[@]} -gt 0 ]; then
  rm -rf "${stale[@]}"
fi

if ! ./kotlin task :sqldelight:compileJvm; then
  echo "SQLDelight plugin warmup failed; running ./kotlin clean fallback"
  ./kotlin clean
  ./kotlin task :sqldelight:compileJvm
fi
