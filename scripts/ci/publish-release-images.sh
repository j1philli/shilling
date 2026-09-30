#!/usr/bin/env bash
set -euo pipefail
# Called only after release.py has validated the version, source and draft release.
: "${SHILLING_RELEASE_TAG:?}"
: "${SHILLING_RELEASE_COMMIT:?}"
: "${SHILLING_RELEASE_TARGETS:?}"
: "${GHCR_TOKEN:?}"
case "$SHILLING_RELEASE_TARGETS" in server|web|server,web) ;; *) exit 1 ;; esac
builder="shilling-release-${TEAMCITY_BUILD_ID:-$$}"
temporary=$(mktemp -d)
export DOCKER_CONFIG="$temporary/docker"
mkdir "$DOCKER_CONFIG"
cleanup() {
    docker buildx rm "$builder" >/dev/null 2>&1 || true
    rm -rf "$temporary"
}
trap cleanup EXIT
docker buildx create --name "$builder" --driver docker-container --use >/dev/null
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u j1philli --password-stdin >/dev/null
for target in ${SHILLING_RELEASE_TARGETS//,/ }; do
    context="$temporary/$target"
    mkdir "$context"
    if [[ "$target" == server ]]; then
        cp release-input/server/server-jvm-executable.jar "$context/"
        cp deploy/self-host/server.Dockerfile "$context/Dockerfile"
    else
        cp -R web-app-dist/. "$context/"
        node scripts/ci/prepare-self-host-web.mjs "$context"
    fi
    docker buildx build --builder "$builder" --platform linux/amd64,linux/arm64 --push \
        --label "org.opencontainers.image.revision=$SHILLING_RELEASE_COMMIT" \
        --label "org.opencontainers.image.version=$SHILLING_RELEASE_TAG" \
        --build-arg "VERSION=$SHILLING_RELEASE_TAG" \
        -t "ghcr.io/j1philli/shilling-$target:$SHILLING_RELEASE_TAG" "$context"
done
