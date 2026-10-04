#!/bin/bash
set -e

SHILLING_DB_NAME="${SHILLING_DB_NAME:-shilling}"
SHILLING_WASM_VARIANT="${SHILLING_WASM_VARIANT:-Release}"

case "$SHILLING_WASM_VARIANT" in
    Debug|Release) ;;
    *)
        echo "ERROR: SHILLING_WASM_VARIANT must be Debug or Release" >&2
        exit 2
        ;;
esac
# Show Settings > Developer tools. The dev `just` recipes turn this on; production builds leave it off.
SHILLING_DEV_TOOLS="${SHILLING_DEV_TOOLS:-false}"
SHILLING_DEVELOPMENT_BUILD=false
if [ "$SHILLING_WASM_VARIANT" = Debug ]; then
    SHILLING_DEVELOPMENT_BUILD=true
fi
# Favicon variant from app/web-app/icons/. The dev `just` recipes use dev; production builds use ga.
SHILLING_ICON_VARIANT="${SHILLING_ICON_VARIANT:-ga}"

case "$SHILLING_ICON_VARIANT" in
    dev|beta|ga) ;;
    *)
        echo "ERROR: SHILLING_ICON_VARIANT must be dev, beta, or ga" >&2
        exit 2
        ;;
esac

echo "Building web-app (wasmJs $SHILLING_WASM_VARIANT package)..."
./kotlin task ":web-app:buildWasmJsAppWasmJs$SHILLING_WASM_VARIANT"

DIST=web-app-dist
PKG_DIR="build/tasks/_web-app_buildWasmJsAppWasmJs$SHILLING_WASM_VARIANT"

rm -rf "$DIST"
mkdir -p "$DIST"
# Kotlin Toolchain packages wasm + skiko + import helpers into PKG_DIR.
# Keep our custom index.html (Tauri logging, error overlay, favicons).
echo "Writing index.html (dev tools: ${SHILLING_DEV_TOOLS})..."
sed -e "s/<meta name=\"shilling-dev-tools\" content=\"false\">/<meta name=\"shilling-dev-tools\" content=\"${SHILLING_DEV_TOOLS}\">/" \
    -e "s/<meta name=\"shilling-development-build\" content=\"false\">/<meta name=\"shilling-development-build\" content=\"${SHILLING_DEVELOPMENT_BUILD}\">/" \
    app/web-app/index.html > "$DIST/index.html"
echo "Copying Wasm package artifacts to $DIST/..."
cp "$PKG_DIR/web-app.mjs" "$DIST/"
cp "$PKG_DIR/web-app.wasm" "$DIST/"
if [ "$SHILLING_WASM_VARIANT" = Release ]; then
    # Optimize the Kotlin app's code/GC types and retain stack-trace names.
    # The unmodified compiler package remains in PKG_DIR for debugging.
    node scripts/optimize-wasm.cjs "$DIST/web-app.wasm"
fi
cp "$PKG_DIR/web-app.import-object.mjs" "$DIST/"
cp "$PKG_DIR/web-app.js-builtins.mjs" "$DIST/"
cp "$PKG_DIR/skiko.mjs" "$DIST/"
cp "$PKG_DIR/skiko.wasm" "$DIST/"
# Needed by the generated web-app.mjs loader in some runtimes.
cp "$PKG_DIR/import-map-loader.js" "$DIST/" 2>/dev/null || true
# Compose Multiplatform resources (e.g. the onboarding logo), fetched at runtime.
cp -R "$PKG_DIR/composeResources" "$DIST/"

# Prefer toolchain-vendored js-joda when present; fall back to npm.
if [ -f "$PKG_DIR/vendors/@js-joda/core/dist/js-joda.esm.js" ]; then
    echo "Copying @js-joda/core from toolchain vendors..."
    cp "$PKG_DIR/vendors/@js-joda/core/dist/js-joda.esm.js" "$DIST/"
elif [ -f "node_modules/@js-joda/core/dist/js-joda.esm.js" ]; then
    echo "Copying @js-joda/core from node_modules..."
    cp node_modules/@js-joda/core/dist/js-joda.esm.js "$DIST/"
else
    echo "ERROR: @js-joda/core not found. Run 'npm ci' first." >&2
    exit 1
fi

# Copy sql.js for SQLDelight web-worker-driver
if [ -f "node_modules/sql.js/dist/sql-wasm.js" ]; then
    echo "Copying sql.js runtime..."
    cp node_modules/sql.js/dist/sql-wasm.js "$DIST/"
    cp node_modules/sql.js/dist/sql-wasm.wasm "$DIST/"
else
    echo "ERROR: sql.js not found. Run 'npm ci' first." >&2
    exit 1
fi

# Copy SQLDelight web worker (custom version that works without a bundler)
echo "Copying SQLDelight worker (DB_NAME=${SHILLING_DB_NAME})..."
sed "s/const DB_NAME = \"shilling\"/const DB_NAME = \"${SHILLING_DB_NAME}\"/" \
    app/web-app/sqldelight.worker.js > "$DIST/sqldelight.worker.js"

echo "Copying $SHILLING_ICON_VARIANT favicons..."
cp "app/web-app/icons/$SHILLING_ICON_VARIANT/favicon.ico" "$DIST/"
cp "app/web-app/icons/$SHILLING_ICON_VARIANT/apple-touch-icon.png" "$DIST/"

echo "Done. Run 'cargo tauri dev' from the project root to launch, or 'just web' to serve."
