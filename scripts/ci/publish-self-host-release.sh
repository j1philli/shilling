#!/usr/bin/env bash
set -euo pipefail

# TeamCity downloads the web bundle and server JAR from builds in this chain.
# Publish only the selected targets from those tested artifacts.
targets="${SHILLING_RELEASE_TARGETS:-server,web}"
case "$targets" in
    server|web|server,web) ;;
    *) echo "SHILLING_RELEASE_TARGETS must be server, web, or server,web" >&2; exit 1 ;;
esac
if [[ ",$targets," == *,server,* ]]; then
    test -f release-input/server/server-jvm-executable.jar
fi
if [[ ",$targets," == *,web,* ]]; then
    test -f web-app-dist/index.html
    test -f web-app-dist/web-app.wasm
fi
command -v docker >/dev/null
docker buildx version >/dev/null
command -v gh >/dev/null

dry_run="${SHILLING_RELEASE_DRY_RUN:-0}"
if [[ "$dry_run" != 0 && "$dry_run" != 1 ]]; then
    echo "SHILLING_RELEASE_DRY_RUN must be 0 or 1" >&2
    exit 1
fi

if [[ "$dry_run" == 0 ]]; then
    : "${GHCR_TOKEN:?Set a TeamCity env.GHCR_TOKEN password parameter with a classic PAT scoped write:packages}"
fi
if [[ -n "${GHCR_TOKEN:-}" ]]; then
    headers="$(mktemp)"
    http_status="$(curl --silent --show-error --dump-header "$headers" --output /dev/null \
        --write-out '%{http_code}' -H "Authorization: Bearer $GHCR_TOKEN" \
        https://api.github.com/user)"
    scopes="$(sed -n 's/^[Xx]-[Oo]auth-[Ss]copes: //p' "$headers" | tr -d ' \r')"
    rm -f "$headers"
    if [[ "$http_status" != 200 || ",$scopes," != *",write:packages,"* ]]; then
        echo "TeamCity GHCR_TOKEN must be a classic PAT with write:packages scope" >&2
        exit 1
    fi
    echo "TeamCity GHCR_TOKEN has write:packages scope"
fi

branch="${BUILD_VCS_BRANCH:-}"
tag="${branch#refs/tags/}"
if [[ "$dry_run" == 0 ]]; then
    if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
        echo "Self-hosted publishing requires a release tag, got: $branch" >&2
        exit 1
    fi
    python3 scripts/ci/check-version.py --tag "$tag"

    # A branch named v* must never be able to publish as if it were a tag.
    git fetch --quiet https://github.com/j1philli/shilling.git "refs/tags/$tag"
    [[ "$(git rev-parse 'FETCH_HEAD^{commit}')" == "$(git rev-parse HEAD)" ]] || {
        echo "Release tag $tag does not point to this checkout" >&2
        exit 1
    }
    git fetch --quiet https://github.com/j1philli/shilling.git refs/heads/main
    git merge-base --is-ancestor HEAD FETCH_HEAD || {
        echo "Release tags must point to a commit on main" >&2
        exit 1
    }

    : "${GITHUB_TOKEN:?Set TeamCity env.GITHUB_TOKEN for GitHub release creation}"
    export GH_TOKEN="$GITHUB_TOKEN"

    # Existing tags/releases are immutable. In particular, do not republish
    # the beta created before this TeamCity pipeline replaced GitHub Actions.
    if gh release view "$tag" --repo j1philli/shilling >/dev/null 2>&1; then
        echo "GitHub release $tag already exists; skipping image publication"
        exit 0
    fi

else
    tag="dry-run"
fi

if [[ ",$targets," == *,web,* ]]; then
    node scripts/ci/prepare-self-host-web.mjs
fi
if [[ ",$targets," == *,server,* ]]; then
    cp deploy/self-host/server.Dockerfile release-input/server/Dockerfile
fi

builder="shilling-release-${TEAMCITY_BUILD_ID:-$$}"
docker_config="$(mktemp -d)"
export DOCKER_CONFIG="$docker_config"
cleanup() {
    docker buildx rm "$builder" >/dev/null 2>&1 || true
    rm -rf "$docker_config"
}
trap cleanup EXIT
docker buildx create --name "$builder" --driver docker-container --use >/dev/null

if [[ "$dry_run" == 1 ]]; then
    for target in ${targets//,/ }; do
        if [[ "$target" == server ]]; then context=release-input/server; else context=web-app-dist; fi
        docker buildx build --builder "$builder" --platform linux/amd64 --load \
            --build-arg "VERSION=$tag" -t "shilling-$target:$tag" "$context"
    done
    echo "Dry run built $targets image(s) from TeamCity artifacts; nothing was published"
    exit 0
fi

printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u j1philli --password-stdin >/dev/null

for target in ${targets//,/ }; do
    if [[ "$target" == server ]]; then
        context=release-input/server
    else
        context=web-app-dist
    fi
    docker buildx build --builder "$builder" \
        --platform linux/amd64,linux/arm64 --push \
        --build-arg "VERSION=$tag" \
        -t "ghcr.io/j1philli/shilling-$target:$tag" "$context"
done

mkdir -p release-assets
assets=()
if [[ "$targets" == server,web ]]; then
    cp deploy/self-host/compose.yaml release-assets/compose.yaml
    printf 'SHILLING_VERSION=%s\n' "$tag" > release-assets/shilling.env.example
    assets=(release-assets/compose.yaml release-assets/shilling.env.example)
fi
release_args=(--repo j1philli/shilling --verify-tag --generate-notes --title "$tag" \
    --notes "Included: self-hosted $targets image(s) for Linux amd64/arm64. Mobile and desktop builds are separate.")
if [[ "$tag" == *-* ]]; then release_args+=(--prerelease); fi
gh release create "$tag" "${assets[@]}" "${release_args[@]}"
