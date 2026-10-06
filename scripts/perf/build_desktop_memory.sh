#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
./kotlin task :perf-web:buildWasmJsAppWasmJsRelease
fixture_dist=perf-web-dist
fixture_package=build/tasks/_perf-web_buildWasmJsAppWasmJsRelease
mkdir -p "$fixture_dist"
cp "$fixture_package"/*.mjs "$fixture_package"/*.wasm "$fixture_dist/"
if [ "${SHILLING_MEMORY_OPTIMIZE_WASM:-true}" = true ]; then
    node scripts/optimize-wasm.cjs "$fixture_dist/perf-web.wasm"
elif [ "${SHILLING_MEMORY_TRIM_NAMES:-true}" = true ]; then
    node scripts/trim-wasm-debug-names.cjs "$fixture_dist/perf-web.wasm"
fi
cp -R "$fixture_package/composeResources" "$fixture_dist/"
cp "$fixture_package/import-map-loader.js" "$fixture_dist/"
cp node_modules/@js-joda/core/dist/js-joda.esm.js "$fixture_dist/"
cp node_modules/sql.js/dist/sql-wasm.js node_modules/sql.js/dist/sql-wasm.wasm "$fixture_dist/"
workload="${SHILLING_MEMORY_WORKLOAD:-all}"
case "$workload" in all|csv|csv-review|idle) ;; *) echo 'SHILLING_MEMORY_WORKLOAD must be all, csv, csv-review or idle' >&2; exit 2;; esac
sed -e 's@./web-app.mjs@./perf-web.mjs@' \
    -e "s@</head>@<meta name=\"shilling-memory-workload\" content=\"$workload\"></head>@" \
    app/web-app/index.html > "$fixture_dist/index.html"
if [ "${SHILLING_MEMORY_SQL_PROFILE:-false}" = true ]; then
    cp app/perf-web/sql-profile.js "$fixture_dist/"
    sed -i '' 's@</head>@<script src="./sql-profile.js"></script></head>@' "$fixture_dist/index.html"
fi
if [ "${SHILLING_MEMORY_RUNTIME_PROFILE:-false}" = true ]; then
    cp app/perf-web/runtime-profile.js "$fixture_dist/"
    sed -i '' 's@</head>@<script src="./runtime-profile.js"></script></head>@' "$fixture_dist/index.html"
fi
sed 's/const DB_NAME = "shilling"/const DB_NAME = "shilling-memory-fixture-v1"/' app/web-app/sqldelight.worker.js > "$fixture_dist/sqldelight.worker.js"
if [ "${SHILLING_MEMORY_SQL_PROFILE:-false}" = true ]; then
    python3 - "$fixture_dist/sqldelight.worker.js" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
marker = 'const data = db.export();'
assert s.count(marker) == 1
p.write_text(s.replace(marker, marker + '''
    self.shillingPersistenceProfile ??= {count: 0, bytes: 0};
    self.shillingPersistenceProfile.count++;
    self.shillingPersistenceProfile.bytes += data.byteLength;
    postMessage({shillingPersistenceProfile: self.shillingPersistenceProfile});'''))
PY
fi
if [ "${SHILLING_MEMORY_ALLOCATION_PROFILE:-false}" = true ]; then
    cp app/perf-web/allocation-profile.js app/perf-web/allocation-worker-profile.js "$fixture_dist/"
    sed -i '' 's@</head>@<script src="./allocation-profile.js"></script></head>@' "$fixture_dist/index.html"
    python3 - "$fixture_dist/sqldelight.worker.js" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
p.write_text('importScripts("allocation-worker-profile.js");\n' + p.read_text() + '''
self.installShillingAllocationProfile(read => {
    requests = requests.then(() => sqlModuleReady).then(() => read(db)).catch(error => {
        postMessage({shillingAllocationProfile: {error: String(error)}});
    });
});
''')
PY
fi
cargo tauri build --bundles app --config '{"productName":"Shilling Memory Perf","identifier":"finance.shilling.perf.memory","build":{"beforeBuildCommand":"","frontendDist":"../perf-web-dist"}}'
