#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Synthetic data only. No existing server, policy file, or worker volume is used.
# Requires OrbStack: its container bridge addresses are reachable from the macOS host.
turn_port="${SHILLING_TEST_TURN_PORT:-3479}"
turn_name="shilling-live-backup-turn-$$"
turn_secret="$(openssl rand -hex 32)"
cleanup() { docker stop "$turn_name" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

docker run --detach --rm --name "$turn_name" \
  -p "127.0.0.1:$turn_port:3478/udp" coturn/coturn:4.7.0 \
  --no-cli --no-tls --no-dtls --listening-ip=0.0.0.0 \
  --min-port=49160 --max-port=49280 --realm=shilling-live-test \
  --use-auth-secret "--static-auth-secret=$turn_secret" \
  --fingerprint --allow-loopback-peers --no-multicast-peers \
  --log-file=stdout --simple-log >/dev/null

./kotlin package -m server -m backup-peer -f executable-jar
export SHILLING_TEST_SERVER_JAR="$PWD/build/tasks/_server_executableJarJvm/server-jvm-executable.jar"
export SHILLING_TEST_WORKER_JAR="$PWD/build/tasks/_backup-peer_executableJarJvm/backup-peer-jvm-executable.jar"
export SHILLING_TEST_LIVE_TURN_URL="turn:127.0.0.1:$turn_port"
export SHILLING_TEST_LIVE_TURN_SECRET="$turn_secret"
if [[ -n "${SHILLING_TEST_WEB_URL:-}" ]]; then
  export SHILLING_TEST_BROWSER_SCRIPT="$PWD/scripts/test-cloud-browser.mjs"
fi
# Keep this native WebRTC acceptance test in its own JVM; run the regular suite with just test.
./kotlin test -m server -p jvm --include-classes=finance.shilling.server.BackupLiveProcessTest
