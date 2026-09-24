#!/bin/bash
set -e

SHILLING_DB_NAME="${SHILLING_DB_NAME:-shilling}"

APP_LOGO_ASSET="app/shared-ui/src/commonMain/kotlin/finance/shilling/shared/ui/AppLogoAsset.kt"
APP_LOGO_SOURCE="app/shared-ui/src/commonMain/composeResources/drawable/app_logo.png"

if [ ! -f "$APP_LOGO_ASSET" ]; then
    echo "Generating AppLogoAsset.kt from tracked app logo..."
    {
        cat <<'EOF'
package finance.shilling.shared.ui

internal object AppLogoAsset {
    internal val base64Png: String = buildString {
EOF
        base64 < "$APP_LOGO_SOURCE" | tr -d '\n' | fold -w 76 | while read -r chunk; do
            printf '        append("%s")\n' "$chunk"
        done
        cat <<'EOF'
    }
}
EOF
    } > "$APP_LOGO_ASSET"
fi

echo "Building web-app (wasmJs debug package)..."
./kotlin task :web-app:buildWasmJsAppWasmJsDebug

DIST=web-app-dist
PKG_DIR=build/tasks/_web-app_buildWasmJsAppWasmJsDebug

mkdir -p "$DIST"
# Kotlin Toolchain packages wasm + skiko + import helpers into PKG_DIR.
# Keep our custom index.html (Tauri logging, error overlay, favicons).
cp app/web-app/index.html "$DIST/"
echo "Copying Wasm package artifacts to $DIST/..."
cp "$PKG_DIR/web-app.mjs" "$DIST/"
cp "$PKG_DIR/web-app.wasm" "$DIST/"
cp "$PKG_DIR/web-app.import-object.mjs" "$DIST/"
cp "$PKG_DIR/web-app.js-builtins.mjs" "$DIST/"
cp "$PKG_DIR/skiko.mjs" "$DIST/"
cp "$PKG_DIR/skiko.wasm" "$DIST/"
# Needed by the generated web-app.mjs loader in some runtimes.
cp "$PKG_DIR/import-map-loader.js" "$DIST/" 2>/dev/null || true

# Prefer toolchain-vendored js-joda when present; fall back to npm.
if [ -f "$PKG_DIR/vendors/@js-joda/core/dist/js-joda.esm.js" ]; then
    echo "Copying @js-joda/core from toolchain vendors..."
    cp "$PKG_DIR/vendors/@js-joda/core/dist/js-joda.esm.js" "$DIST/"
elif [ -f "node_modules/@js-joda/core/dist/js-joda.esm.js" ]; then
    echo "Copying @js-joda/core from node_modules..."
    cp node_modules/@js-joda/core/dist/js-joda.esm.js "$DIST/"
else
    echo "WARNING: @js-joda/core not found. Run 'npm install' first."
fi

# Copy sql.js for SQLDelight web-worker-driver
if [ -f "node_modules/sql.js/dist/sql-wasm.js" ]; then
    echo "Copying sql.js runtime..."
    cp node_modules/sql.js/dist/sql-wasm.js "$DIST/"
    cp node_modules/sql.js/dist/sql-wasm.wasm "$DIST/"
else
    echo "WARNING: sql.js not found. Run 'npm install' first."
fi

# Copy SQLDelight web worker (custom version that works without a bundler)
echo "Copying SQLDelight worker (DB_NAME=${SHILLING_DB_NAME})..."
sed "s/const DB_NAME = \"shilling\"/const DB_NAME = \"${SHILLING_DB_NAME}\"/" \
    app/web-app/sqldelight.worker.js > "$DIST/sqldelight.worker.js"

# Copy favicon and icons
cp app/web-app/favicon.ico "$DIST/" 2>/dev/null || true
cp app/web-app/apple-touch-icon.png "$DIST/" 2>/dev/null || true

echo "Done. Run 'cargo tauri dev' from the project root to launch, or 'just web' to serve."
