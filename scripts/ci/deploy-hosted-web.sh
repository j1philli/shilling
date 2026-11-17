#!/usr/bin/env bash
set -euo pipefail
# This script is invoked only by the manual release workflow after validation.
: "${SHILLING_RELEASE_COMMIT:?}"
: "${CLOUDFLARE_ACCOUNT_ID:?}"
: "${CLOUDFLARE_PAGES_PROJECT:?}"
export CLOUDFLARE_API_TOKEN="${CLOUDFLARE_API_TOKEN:-${CF_API_TOKEN:-}}"
: "${CLOUDFLARE_API_TOKEN:?}"
unset CF_API_TOKEN
# The image build uses a copy; this must remain the hosted bundle.
if grep -q 'SHILLING_SELF_HOSTED_ONLY' web-app-dist/index.html; then
    echo 'Refusing to deploy a self-hosted bundle to production Pages' >&2
    exit 1
fi
npx --yes wrangler pages deploy web-app-dist/ \
    --project-name="$CLOUDFLARE_PAGES_PROJECT" --branch=main \
    --commit-hash="$SHILLING_RELEASE_COMMIT" --commit-dirty=false
node scripts/ci/ensure-pages-domain.mjs
