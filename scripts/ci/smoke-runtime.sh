#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

test -s release-input/server/server-jvm-executable.jar
test -s web-app-dist/web-app.wasm
export SHILLING_SMOKE_TAG="ci-${TEAMCITY_BUILD_ID:-$$}"
project="shilling-smoke-$SHILLING_SMOKE_TAG"
compose=(docker compose -p "$project" -f scripts/ci/smoke/compose.yaml)
context=$(mktemp -d)
results="$PWD/smoke-results/runtime"
rm -rf "$results"
mkdir -p "$results"
cleanup() {
    "${compose[@]}" logs --no-color > "$results/containers.log" 2>&1 || true
    container=$("${compose[@]}" ps -aq tests 2>/dev/null || true)
    if [ -n "$container" ]; then docker cp "$container:/smoke/results/." "$results/" >/dev/null 2>&1 || true; fi
    if [ -f "$results/junit.xml" ] && [ -n "${TEAMCITY_VERSION:-}" ]; then
        echo "##teamcity[importData type='junit' path='smoke-results/runtime/junit.xml']"
    fi
    "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
    for target in server web hosted-web tests; do docker image rm "shilling-smoke-$target:$SHILLING_SMOKE_TAG" >/dev/null 2>&1 || true; done
    rm -rf "$context"
}
trap cleanup EXIT

cp -R web-app-dist "$context/web"
cp -R web-app-dist "$context/hosted-web"
node scripts/ci/prepare-self-host-web.mjs "$context/web"
sed 's/server:8081/hosted-server:8081/g' deploy/self-host/Caddyfile > "$context/hosted-web/Caddyfile"
docker build --build-arg "VERSION=$(cat VERSION)" -t "shilling-smoke-server:$SHILLING_SMOKE_TAG" \
    -f deploy/self-host/server.Dockerfile release-input/server
docker build --build-arg "VERSION=$(cat VERSION)" -t "shilling-smoke-web:$SHILLING_SMOKE_TAG" "$context/web"
docker build --build-arg "VERSION=$(cat VERSION)" -t "shilling-smoke-hosted-web:$SHILLING_SMOKE_TAG" \
    -f deploy/self-host/web.Dockerfile "$context/hosted-web"
docker build -t "shilling-smoke-tests:$SHILLING_SMOKE_TAG" scripts/ci/smoke
"${compose[@]}" up -d server auth-fixture hosted-server web hosted-web
"${compose[@]}" up --no-deps --abort-on-container-exit --exit-code-from tests tests
