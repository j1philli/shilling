#!/usr/bin/env bash
set -euo pipefail

export PATH="$HOME/.cargo/bin:$PATH"
test -f web-app-dist/index.html
test -f web-app-dist/web-app.wasm
command -v cargo >/dev/null
command -v cargo-tauri >/dev/null
for target in aarch64-apple-darwin x86_64-apple-darwin; do
    if ! rustup target list --installed | grep -qx "$target"; then
        echo "Missing Rust target $target; run scripts/ci/setup-macos-agent.sh" >&2
        exit 1
    fi
done

logo=app/shared-ui/src/commonMain/composeResources/drawable/app_logo.png
test -f "$logo"
cargo tauri icon "$logo" --output src-tauri/icons

# The web bundle came from the Web Deploy build in this same TeamCity chain.
cargo tauri build --bundles dmg --target universal-apple-darwin --no-sign \
    --config '{"build":{"beforeBuildCommand":""}}'

output="$PWD/desktop-artifacts/macos"
rm -rf "$output"
mkdir -p "$output"
find src-tauri/target/universal-apple-darwin/release/bundle/dmg -maxdepth 1 -type f -name '*.dmg' \
    -exec cp {} "$output/" \;

if ! find "$output" -maxdepth 1 -type f -name '*.dmg' -print -quit | grep -q .; then
    echo "Missing macOS DMG package" >&2
    exit 1
fi

echo "macOS desktop packages:"
find "$output" -maxdepth 1 -type f -print
