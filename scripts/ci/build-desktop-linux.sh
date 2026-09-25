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
cp deploy/desktop/linux.Dockerfile "$context/Dockerfile"

output="$PWD/desktop-artifacts/linux"
mkdir -p "$output"
docker buildx build --platform linux/amd64 --progress plain \
    --output "type=local,dest=$output" "$context"

for extension in deb rpm AppImage; do
    if ! find "$output" -maxdepth 1 -type f -name "*.$extension" -print -quit | grep -q .; then
        echo "Missing Linux .$extension desktop package" >&2
        exit 1
    fi
done

echo "Linux desktop packages:"
find "$output" -maxdepth 1 -type f -print
