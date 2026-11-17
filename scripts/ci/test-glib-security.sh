#!/usr/bin/env bash
# Check the vendored GLib safety fix with optimizations enabled.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
CARGO_TARGET_DIR="$root/src-tauri/target/glib-security" \
    cargo test --manifest-path "$root/third_party/glib/Cargo.toml" --release --lib variant_iter::tests
