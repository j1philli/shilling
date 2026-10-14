#!/usr/bin/env bash
set -euo pipefail
set +x

# Clear stale artifacts even when configuration/preflight fails on a reused
# TeamCity checkout; a failed signed run must not expose last run's installer.
output="$PWD/desktop-artifacts/windows"
rm -rf "$output"
mkdir -p "$output"

signed="${SHILLING_WINDOWS_SIGNED:-0}"
case "$signed" in
    0|1) ;;
    *) echo "SHILLING_WINDOWS_SIGNED must be 0 or 1" >&2; exit 1 ;;
esac

test -f web-app-dist/index.html
test -f web-app-dist/web-app.wasm
command -v docker >/dev/null
docker buildx version >/dev/null

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
context="$scratch/context"
mkdir -p "$context/src-tauri"
cp -R web-app-dist "$context/"
# Agent checkouts can contain previous target/gen output. Never upload it as
# build context or let a cached signed executable leak into an unsigned build.
tar -C src-tauri --exclude=./target --exclude=./gen -cf - . | tar -C "$context/src-tauri" -xf -
cp deploy/desktop/windows-cross.Dockerfile "$context/Dockerfile"
cp scripts/ci/windows_signing.py "$context/windows_signing.py"

build_args=(--platform linux/amd64 --progress plain --no-cache-filter package
    --build-arg "SHILLING_WINDOWS_SIGNED=$signed")
if [ "$signed" = 1 ]; then
    python3 scripts/ci/windows_signing.py prepare "$scratch/signing.json"
    build_args+=(--secret "id=windows_signing,src=$scratch/signing.json")
fi

# Secrets do not invalidate BuildKit caches. Always rerun packaging/signing;
# Cargo's cache mounts still reuse the expensive compilation work.
docker buildx build "${build_args[@]}" \
    --output "type=local,dest=$output" "$context"

if ! find "$output" -maxdepth 1 -type f -name '*.exe' -print -quit | grep -q .; then
    echo "Missing Windows NSIS desktop installer" >&2
    exit 1
fi

echo "Windows desktop installer:"
find "$output" -maxdepth 1 -type f -print
if [ "$signed" = 1 ]; then
    echo "Azure Authenticode signing and Linux signature verification passed."
else
    echo "Unsigned development installer (publisher signing was not requested)."
fi
