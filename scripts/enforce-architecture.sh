#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

fail=0

check_absent() {
  local pattern="$1"
  local description="$2"
  if rg -n "$pattern" \
    AGENTS.md CLAUDE.md app core server docs \
    --glob '!docs/server/entity-sync.mdx' \
    --glob '!docs/server/overview.mdx' \
    --glob '!node_modules' >/tmp/shilling-arch-check.$$ 2>/dev/null; then
    echo "forbidden: $description"
    cat /tmp/shilling-arch-check.$$
    fail=1
  fi
  rm -f /tmp/shilling-arch-check.$$
}

check_absent_in() {
  local pattern="$1"
  local description="$2"
  shift 2
  if rg -n "$pattern" "$@" >/tmp/shilling-arch-check.$$ 2>/dev/null; then
    echo "forbidden: $description"
    cat /tmp/shilling-arch-check.$$
    fail=1
  fi
  rm -f /tmp/shilling-arch-check.$$
}

check_absent 'SignalingMessage\.SyncData' 'signaling relay of user data'
check_absent 'SignalingRelayManager' 'signaling-only user-data transport'
check_absent 'fetchChangesSince\(' 'server catch-up for user data'
check_absent 'pushChange\(' 'server push of user data'
check_absent 'fetchAccounts\(' 'server-backed account data fetch'
check_absent 'fetchCategories\(' 'server-backed category data fetch'
check_absent 'fetchSchedules\(' 'server-backed schedule data fetch'
check_absent 'fetchPostings\(' 'server-backed posting data fetch'
check_absent 'fetchReceipts\(' 'server-backed receipt data fetch'

REPOSITORY_FILES=(
  app/shared/src/commonMain/kotlin/finance/shilling/shared/data/store/*Repository.kt
)
SYNC_FILES=(
  app/shared/src/commonMain/kotlin/finance/shilling/shared/data/sync/*.kt
  app/shared/src@nonJvm/finance/shilling/shared/data/sync/*.kt
)
# All commonMain code under app/shared/.../data/ EXCEPT the store/ package must go through
# Store5. The store/ directory itself holds the SourceOfTruth and Store5-internal sync
# metadata helpers, so it's the only place in commonMain where direct SQLDelight access
# is permitted. Tests under app/shared/test@jvm are exempt and handled separately.
SHARED_DATA_NON_STORE_FILES=(
  app/shared/src/commonMain/kotlin/finance/shilling/shared/data/*.kt
  app/shared/src/commonMain/kotlin/finance/shilling/shared/data/auth/*.kt
  app/shared/src/commonMain/kotlin/finance/shilling/shared/data/usecase/*.kt
)

check_absent_in 'private val store: [A-Za-z0-9_]+Store\?' \
  'nullable Store wrappers in repositories (Store5 fallback path)' \
  "${REPOSITORY_FILES[@]}"
check_absent_in 'db\.[A-Za-z0-9_]+Queries\.[A-Za-z0-9_]+\(' \
  'direct SQLDelight queries inside repositories (reads and writes must go through Store5)' \
  "${REPOSITORY_FILES[@]}"
check_absent_in 'db\.transaction\(' \
  'direct SQLDelight transactions inside repositories (must go through Store5)' \
  "${REPOSITORY_FILES[@]}"
check_absent_in 'ShillingDatabase|db\.[A-Za-z0-9_]+Queries\.[A-Za-z0-9_]+\(|db\.transaction\(' \
  'direct SQLDelight access inside sync layer (must go through app/shared/.../data/store/)' \
  "${SYNC_FILES[@]}"
check_absent_in '\.[A-Za-z0-9_]+Queries\.[A-Za-z0-9_]+\(|\.transaction\(' \
  'direct SQLDelight access outside app/shared/.../data/store/ (all entity + metadata I/O must go through Store5 repositories or the store-layer helpers that own those tables)' \
  "${SHARED_DATA_NON_STORE_FILES[@]}"

if [ "$fail" -ne 0 ]; then
  exit 1
fi

echo "architecture guard passed"
