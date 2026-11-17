#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Disposable PostgreSQL with Supabase API roles; never connects to a hosted project.
pg_name="shilling-backup-postgres-$$"
pg_password="$(openssl rand -hex 32)"
cloud_network="shilling-cloud-test-$$"
storage_name="shilling-cloud-storage-$$"
proxy_name="shilling-cloud-proxy-$$"
cloud_config="$(mktemp -d)"
export SHILLING_TEST_JWT_SECRET="$(openssl rand -hex 32)"
cleanup() {
  docker stop "$proxy_name" "$storage_name" "$pg_name" >/dev/null 2>&1 || true
  docker network rm "$cloud_network" >/dev/null 2>&1 || true
  rm -rf "$cloud_config"
}
trap cleanup EXIT INT TERM
docker network create "$cloud_network" >/dev/null
docker run --detach --rm --network "$cloud_network" --network-alias cloud-db --name "$pg_name" -p 127.0.0.1::5432 \
  -e "POSTGRES_PASSWORD=$pg_password" postgres:17-alpine >/dev/null
for attempt in {1..60}; do
  if docker exec "$pg_name" pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec -i "$pg_name" psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE ROLE service_role BYPASSRLS;
CREATE ROLE supabase_storage_admin LOGIN SUPERUSER;
CREATE SCHEMA auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT NULLIF(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
SQL
docker exec -i "$pg_name" psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 \
  < supabase/cloud-peer.sql >/dev/null
# Random hex secret generated above is safe as a SQL literal; no production secrets used.
printf "ALTER ROLE shilling_cloud_control LOGIN PASSWORD '%s';\nALTER ROLE shilling_backup_worker LOGIN PASSWORD '%s';\n" "$pg_password" "$pg_password" |
  docker exec -i "$pg_name" psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 >/dev/null
pg_port="$(docker port "$pg_name" 5432/tcp | cut -d: -f2)"
export SHILLING_TEST_BACKUP_DATABASE_URL="jdbc:postgresql://127.0.0.1:$pg_port/postgres?sslmode=disable"
export SHILLING_TEST_BACKUP_DATABASE_USER=shilling_backup_worker
export SHILLING_TEST_BACKUP_DATABASE_PASSWORD="$pg_password"
# Run the actual Supabase Storage API with disposable file-backed object storage.
export SHILLING_TEST_STORAGE_KEY="$(python3 - <<'JWT'
import os, json, base64, hmac, hashlib, time
enc=lambda v:base64.urlsafe_b64encode(v).rstrip(b'=')
parts=[enc(json.dumps({'alg':'HS256','typ':'JWT'}).encode()),enc(json.dumps({'role':'service_role','iss':'supabase','iat':int(time.time()),'exp':int(time.time())+3600}).encode())]
payload=b'.'.join(parts)
print((payload+b'.'+enc(hmac.new(os.environ['SHILLING_TEST_JWT_SECRET'].encode(),payload,hashlib.sha256).digest())).decode())
JWT
)"
docker run --detach --rm --network "$cloud_network" --network-alias cloud-storage --name "$storage_name" \
  -e "DATABASE_URL=postgres://postgres:$pg_password@cloud-db:5432/postgres" \
  -e "AUTH_JWT_SECRET=$SHILLING_TEST_JWT_SECRET" -e "SERVICE_KEY=$SHILLING_TEST_STORAGE_KEY" \
  -e "ANON_KEY=$SHILLING_TEST_STORAGE_KEY" -e POSTGREST_URL=http://unused:3000 \
  -e STORAGE_BACKEND=file -e FILE_STORAGE_BACKEND_PATH=/var/lib/storage -e GLOBAL_S3_BUCKET=stub \
  -e FILE_SIZE_LIMIT=117440512 -e TENANT_ID=local -e REGION=local -e ENABLE_IMAGE_TRANSFORMATION=false \
  supabase/storage-api:v1.74.0 >/dev/null
cat > "$cloud_config/default.conf" <<'NGINX'
server { listen 80; client_max_body_size 112m;
  location /storage/v1/ { proxy_pass http://cloud-storage:5000/; proxy_read_timeout 60s; }
}
NGINX
docker run --detach --rm --network "$cloud_network" --name "$proxy_name" -p 127.0.0.1::80 \
  -v "$cloud_config/default.conf:/etc/nginx/conf.d/default.conf:ro" nginx:alpine >/dev/null
storage_port="$(docker port "$proxy_name" 80/tcp | cut -d: -f2)"
export SHILLING_TEST_STORAGE_URL="http://127.0.0.1:$storage_port"
ready=0
for attempt in {1..60}; do
  if curl -fsS "$SHILLING_TEST_STORAGE_URL/storage/v1/status" >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then docker logs "$storage_name" --tail 20; exit 1; fi
# Plain Postgres lacks the schema grants normally provisioned by Supabase itself.
docker exec -i "$pg_name" psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
GRANT USAGE ON SCHEMA storage TO anon,authenticated,service_role;
GRANT ALL ON ALL TABLES IN SCHEMA storage TO anon,authenticated,service_role;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA storage TO anon,authenticated,service_role;
SQL
docker exec -i "$pg_name" psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 < supabase/cloud-storage.sql >/dev/null
# Fail before starting JVM tests if the real Storage API cannot authorize its private bucket.
curl -sS -H "Authorization: Bearer $SHILLING_TEST_STORAGE_KEY" "$SHILLING_TEST_STORAGE_URL/storage/v1/bucket/cloud-peer" > "$cloud_config/bucket.json"
python3 - "$cloud_config/bucket.json" <<'CHECK'
import json,sys
value=json.load(open(sys.argv[1]))
if value.get('id') != 'cloud-peer' or value.get('public') is not False:
    print('Storage readiness error:', {k:value.get(k) for k in ('statusCode','error','message')})
    raise SystemExit(1)
CHECK
./kotlin test -m shared -p jvm --include-classes=finance.shilling.shared.data.backup.BackupDatabaseTest --include-classes=finance.shilling.shared.data.backup.HostedStorageTest
if [[ "${SHILLING_TEST_STORAGE_ONLY:-0}" != 1 ]]; then scripts/test-live-backup.sh; fi
