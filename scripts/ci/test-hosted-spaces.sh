#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
container="shilling-spaces-policy-${RANDOM}-$$"
trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT
docker run --name "$container" -e POSTGRES_PASSWORD=local-test-only -d postgres:17 >/dev/null
for attempt in $(seq 1 30); do
  if docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
for migration in scripts/ci/fixtures/hosted-spaces-bootstrap.sql \
 supabase/migrations/20260926120000_hosted_spaces.sql \
 supabase/migrations/20260926121000_hosted_devices.sql \
 supabase/migrations/20260926122000_hosted_device_owners.sql \
 supabase/migrations/20261001183000_hosted_space_management.sql \
 scripts/ci/fixtures/hosted-spaces-assertions.sql; do
  docker exec -i "$container" psql -U postgres -v ON_ERROR_STOP=1 < "$migration"
done
