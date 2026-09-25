#!/usr/bin/env bash
set -euo pipefail

test -f web-app-dist/index.html
test -f web-app-dist/web-app.wasm
command -v docker >/dev/null
docker buildx version >/dev/null

context="$(mktemp -d)"
trap 'rm -rf "$context"' EXIT
cp -R src-tauri web-app-dist "$context/"
cp app/shared-ui/src/commonMain/composeResources/drawable/app_logo.png "$context/app_logo.png"
cp deploy/desktop/windows-cross.Dockerfile "$context/Dockerfile"

output="$PWD/desktop-artifacts/windows"
mkdir -p "$output"
docker buildx build --platform linux/amd64 --progress plain \
    --output "type=local,dest=$output" "$context"

if ! find "$output" -maxdepth 1 -type f -name '*.exe' -print -quit | grep -q .; then
    echo "Missing Windows NSIS desktop installer" >&2
    exit 1
fi

echo "Windows desktop installer:"
find "$output" -maxdepth 1 -type f -print
