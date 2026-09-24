# Shilling — Kotlin Multiplatform budgeting app

set dotenv-load := false

# Default: list available recipes
default:
    @just --list

# ─── Icons ────────────────────────────────────────────────────────────

# Apply icon variant to all platforms (dev/beta/ga)
apply-icons variant='dev':
    ./scripts/apply-icon-variant.sh {{variant}}

# Regenerate all icon variants from source (requires ImageMagick)
generate-icons:
    ./scripts/generate-icons.sh

# ─── Setup ─────────────────────────────────────────────────────────

# One-time setup after fresh clone (deps + icons + WebRTC framework)
setup: deps generate-icons setup-webrtc

# ─── Build ───────────────────────────────────────────────────────────

# Workaround: Amper cache state around sqldelight-plugin can become inconsistent.
# We clear incremental state, ensure expected output dirs exist, then warm the
# plugin compile task. If warmup fails, fall back to full clean once.
[private]
ensure-plugin:
    #!/usr/bin/env bash
    set -euo pipefail
    shopt -s nullglob

    stale=(
      build/tasks/_sqldelight-plugin_*
      build/incremental.state/_sqldelight-plugin_*
      build/incremental.state/_shared_generateSqlDelight_*
      build/incremental.state/_shared_compile*
    )
    if [ ${#stale[@]} -gt 0 ]; then
      rm -rf "${stale[@]}"
    fi

    # Amper sometimes expects both output roots in this artifact directory.
    mkdir -p build/artifacts/CompiledJvmClassesArtifact/sqldelight-pluginjvm/java-output
    mkdir -p build/artifacts/CompiledJvmClassesArtifact/sqldelight-pluginjvm/kotlin-output

    if ! ./kotlin task :sqldelight-plugin:compileJvm; then
      echo "sqldelight-plugin warmup failed; running ./kotlin clean fallback"
      ./kotlin clean
      mkdir -p build/artifacts/CompiledJvmClassesArtifact/sqldelight-pluginjvm/java-output
      mkdir -p build/artifacts/CompiledJvmClassesArtifact/sqldelight-pluginjvm/kotlin-output
      ./kotlin task :sqldelight-plugin:compileJvm
    fi

# Build only the wasmJs web-app (dev database)
build-web: deps ensure-plugin
    ./scripts/apply-icon-variant.sh "${SHILLING_ICON_VARIANT:-dev}"
    SHILLING_DB_NAME=shilling-dev SHILLING_WASM_VARIANT=Debug ./build-web.sh

# Build everything (all platforms, all modules)
build-all: setup-webrtc ensure-plugin
    ./kotlin build

# Clean all build outputs and caches
clean:
    ./kotlin clean

# Clear persisted desktop app state (Tauri dev shell + WebKit storage)
clear-desktop:
    #!/usr/bin/env bash
    set -euo pipefail
    shopt -s nullglob

    # Stop the dev shell first so WebKit/preferences state is not recreated
    # while the reset is in progress.
    pkill -x shilling >/dev/null 2>&1 || true
    pkill -x Shilling >/dev/null 2>&1 || true
    pkill -if '/cargo-tauri tauri dev' >/dev/null 2>&1 || true
    sleep 1

    paths=(
      "$HOME/Library/Application Support/finance.shilling.desktop"
      "$HOME/Library/Application Support/shilling"
      "$HOME/Library/Caches/finance.shilling.desktop"
      "$HOME/Library/Caches/shilling"
      "$HOME/Library/WebKit/finance.shilling.desktop"
      "$HOME/Library/WebKit/shilling"
      "$HOME/Library/Preferences/finance.shilling.desktop.plist"
      "$HOME/Library/Preferences/shilling.plist"
      "$HOME/Library/Saved Application State/finance.shilling.desktop.savedState"
      "$HOME/Library/Saved Application State/shilling.savedState"
      "$HOME/Library/Containers/finance.shilling.desktop"
      "$HOME/Library/Containers/shilling"
      "$HOME/Library/Logs/finance.shilling.desktop"
      "$HOME/Library/Logs/com.shilling.desktop"
    )

    removed=0
    for path in "${paths[@]}"; do
        if [ -e "$path" ]; then
            rm -rf "$path"
            echo "Removed $path"
            removed=$((removed + 1))
        fi
    done

    echo "Cleared desktop state ($removed paths)"

# Clear Android app state on a connected emulator or device
clear-android serial='':
    #!/usr/bin/env bash
    set -euo pipefail

    ADB="$HOME/Library/Android/sdk/platform-tools/adb"
    SERIAL="{{serial}}"
    if [ -z "$SERIAL" ]; then
        SERIAL=$("$ADB" devices | awk '/device$/{print $1}' | head -1)
    fi

    if [ -z "$SERIAL" ]; then
        echo "Error: no Android emulator or device found" >&2
        exit 1
    fi

    "$ADB" -s "$SERIAL" shell pm clear finance.shilling.android
    echo "Cleared Android state on $SERIAL"

# Clear iOS simulator app state
clear-ios:
    #!/usr/bin/env bash
    set -euo pipefail

    xcrun simctl uninstall booted finance.shilling.app >/dev/null 2>&1 || true
    echo "Cleared iOS simulator state for finance.shilling.app"

# ─── Run ─────────────────────────────────────────────────────────────

# Build and launch Android app on emulator
android: ensure-plugin
    #!/usr/bin/env bash
    set -euo pipefail
    ./scripts/apply-icon-variant.sh "${SHILLING_ICON_VARIANT:-dev}"
    AVD="${ANDROID_AVD:-Pixel_9_Pro_XL_API_35}"
    EMU="$HOME/Library/Android/sdk/emulator/emulator"
    ADB="$HOME/Library/Android/sdk/platform-tools/adb"

    # Start emulator with a visible window if not already running
    if ! "$ADB" devices | grep -q "emulator-"; then
        echo "==> Starting emulator ($AVD)..."
        "$EMU" -avd "$AVD" -no-snapshot-load &
        # Wait for the emulator to boot
        echo "==> Waiting for emulator to boot..."
        "$ADB" wait-for-device
        "$ADB" shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
        echo "==> Emulator ready."
    else
        echo "==> Emulator already running."
    fi

    SERIAL=$("$ADB" devices | grep "emulator-" | head -1 | awk '{print $1}')

    echo "==> Building android-app..."
    ./kotlin build -m android-app

    echo "==> Installing APK..."
    APK=$(find build/tasks -name "*.apk" -path "*android-app*" | head -1)
    "$ADB" -s "$SERIAL" install -r "$APK"

    echo "==> Launching app..."
    "$ADB" -s "$SERIAL" shell am start -n finance.shilling.android/.MainActivity

    echo "==> Attaching to logs (Ctrl-C to stop)..."
    sleep 1
    PID=$("$ADB" -s "$SERIAL" shell pidof finance.shilling.android 2>/dev/null || true)
    if [ -n "$PID" ]; then
        "$ADB" -s "$SERIAL" logcat --pid="$PID"
    else
        "$ADB" -s "$SERIAL" logcat -s "Android:*" "Shilling:*" "AndroidRuntime:E"
    fi

# Launch desktop app (Tauri + wasmJs)
desktop: build-web
    cargo tauri dev

# Serve wasmJs app in browser with live reload (auto-rebuilds on source changes)
web: build-web
    #!/usr/bin/env bash
    set -euo pipefail

    cleanup() {
        echo ""
        echo "Stopping web dev server..."
        [ -n "${BS_PID:-}" ] && kill $BS_PID 2>/dev/null
        wait 2>/dev/null
    }
    trap cleanup EXIT INT TERM

    # Start static file server
    npx serve web-app-dist --config ../serve.json &
    BS_PID=$!

    # Create a timestamp marker for change detection
    MARKER=$(mktemp)
    touch "$MARKER"

    echo ""
    echo "Watching for source changes... (Ctrl-C to stop)"
    echo ""

    while true; do
        sleep 2
        # Check if any source files are newer than the last build
        CHANGED=$(find app/shared/src app/shared-ui/src app/web-app/src app/web-app/index.html \
            -newer "$MARKER" -name "*.kt" -o -newer "$MARKER" -name "*.html" 2>/dev/null | head -1)
        if [ -n "$CHANGED" ]; then
            echo "==> Source change detected, rebuilding..."
            touch "$MARKER"
            if SHILLING_DB_NAME=shilling-dev SHILLING_WASM_VARIANT=Debug ./build-web.sh; then
                echo "==> Rebuild complete."
            else
                echo "==> Rebuild failed!"
            fi
        fi
    done

# Download WebRTC.xcframework for iOS (idempotent)
setup-webrtc:
    ./setup-webrtc.sh

# Launch iOS app on Simulator and stream logs
ios: setup-webrtc ensure-plugin
    #!/usr/bin/env bash
    set -euo pipefail
    ./kotlin run ios-app -p iosSimulatorArm64
    echo "==> App launched, attaching to logs..."
    sleep 1
    DATA_DIR="$(xcrun simctl get_app_container booted finance.shilling.app data 2>/dev/null)"
    if [ -z "$DATA_DIR" ]; then
        echo "Warning: could not find finance.shilling.app data container, skipping log tail" >&2
        exit 0
    fi
    LOG_FILE="${DATA_DIR}/tmp/shilling-ios.log"
    : > "$LOG_FILE"
    tail -f "$LOG_FILE"

# Build and deploy Android app to connected real device
android-device:
    #!/usr/bin/env bash
    set -euo pipefail
    ADB="$HOME/Library/Android/sdk/platform-tools/adb"

    # Find real devices (not emulators)
    DEVICE_LINES=$("$ADB" devices | grep -v "emulator-" | grep "device$" | awk '{print $1}' || true)
    if [ -z "$DEVICE_LINES" ]; then
        echo "Error: No real Android device connected."
        echo "  - Connect via USB and enable USB debugging"
        echo "  - Or connect via WiFi: adb pair <ip>:<port>"
        exit 1
    fi

    DEVICE_COUNT=$(echo "$DEVICE_LINES" | wc -l | tr -d ' ')
    if [ "$DEVICE_COUNT" -eq 1 ]; then
        SERIAL="$DEVICE_LINES"
    else
        echo "==> Multiple Android devices found:"
        echo ""
        i=1
        while IFS= read -r line; do
            # Try to get a friendly model name
            MODEL=$("$ADB" -s "$line" shell getprop ro.product.model 2>/dev/null || echo "unknown")
            echo "  $i) $line ($MODEL)"
            i=$((i + 1))
        done <<< "$DEVICE_LINES"
        echo ""
        printf "Select device [1-%d]: " "$DEVICE_COUNT"
        read -r CHOICE
        if ! [[ "$CHOICE" =~ ^[0-9]+$ ]] || [ "$CHOICE" -lt 1 ] || [ "$CHOICE" -gt "$DEVICE_COUNT" ]; then
            echo "Error: Invalid selection."
            exit 1
        fi
        SERIAL=$(echo "$DEVICE_LINES" | sed -n "${CHOICE}p")
    fi

    DEVICE_MODEL=$("$ADB" -s "$SERIAL" shell getprop ro.product.model 2>/dev/null || echo "$SERIAL")
    echo "==> Selected device: $DEVICE_MODEL ($SERIAL)"

    LAN_IP=$(ipconfig getifaddr en0 2>/dev/null || echo "unknown")
    echo "==> Host LAN IP: $LAN_IP"
    echo "    Set server URL to http://$LAN_IP:8081 in DevView on the device"
    echo ""

    echo "==> Preparing build system..."
    just ensure-plugin

    echo "==> Building android-app..."
    ./kotlin build -m android-app

    echo "==> Installing APK..."
    APK=$(find build/tasks -name "*.apk" -path "*android-app*" | head -1)
    if [ -z "$APK" ]; then
        echo "Error: No APK found in build/tasks"
        exit 1
    fi
    "$ADB" -s "$SERIAL" install -r "$APK"

    echo "==> Launching app..."
    "$ADB" -s "$SERIAL" shell am start -n finance.shilling.android/.MainActivity

    echo "==> Attaching to logs (Ctrl-C to stop)..."
    sleep 1
    PID=$("$ADB" -s "$SERIAL" shell pidof finance.shilling.android 2>/dev/null || true)
    if [ -n "$PID" ]; then
        "$ADB" -s "$SERIAL" logcat --pid="$PID"
    else
        "$ADB" -s "$SERIAL" logcat -s "Android:*" "Shilling:*" "AndroidRuntime:E"
    fi

# Build and deploy iOS app to connected real device
ios-device:
    #!/usr/bin/env bash
    set -euo pipefail

    TEAM_ID="${IOS_TEAM_ID:-7QN6HR273V}"

    # Find connected physical iOS devices (only from == Devices == section)
    DEVICE_LINES=$(xcrun xctrace list devices 2>/dev/null \
        | sed -n '/^== Devices ==/,/^== /{/^==/d;p;}' \
        | grep -v "^$" \
        | grep -v "MacBook" | grep -v "Watch" | grep -v "Offline" \
        | grep "iPhone\|iPad" || true)
    if [ -z "$DEVICE_LINES" ]; then
        echo "Error: No real iOS device found. Connect via USB and trust the computer."
        exit 1
    fi

    DEVICE_COUNT=$(echo "$DEVICE_LINES" | wc -l | tr -d ' ')
    if [ "$DEVICE_COUNT" -eq 1 ]; then
        SELECTED="$DEVICE_LINES"
    else
        echo "==> Multiple iOS devices found:"
        echo ""
        i=1
        while IFS= read -r line; do
            echo "  $i) $line"
            i=$((i + 1))
        done <<< "$DEVICE_LINES"
        echo ""
        printf "Select device [1-%d]: " "$DEVICE_COUNT"
        read -r CHOICE
        if ! [[ "$CHOICE" =~ ^[0-9]+$ ]] || [ "$CHOICE" -lt 1 ] || [ "$CHOICE" -gt "$DEVICE_COUNT" ]; then
            echo "Error: Invalid selection."
            exit 1
        fi
        SELECTED=$(echo "$DEVICE_LINES" | sed -n "${CHOICE}p")
    fi

    UDID=$(echo "$SELECTED" | sed 's/.*(\([0-9A-Fa-f-]*\))$/\1/')
    if [ -z "$UDID" ]; then
        echo "Error: Could not parse UDID from: $SELECTED"
        exit 1
    fi
    DEVICE_NAME=$(echo "$SELECTED" | sed 's/ *(.*//')
    echo "==> Selected device: $DEVICE_NAME ($UDID)"

    LAN_IP=$(ipconfig getifaddr en0 2>/dev/null || echo "unknown")
    echo "==> Host LAN IP: $LAN_IP"
    echo "    Set server URL to http://$LAN_IP:8081 in DevView on the device"
    echo ""

    echo "==> Preparing build system..."
    just setup-webrtc
    just ensure-plugin

    echo "==> Building Kotlin framework with Amper (iosArm64)..."
    ./kotlin build -m ios-app -p iosArm64

    echo "==> Building and signing with Xcode..."
    PROJ_DIR="app/ios-app/module.xcodeproj"
    export KOTLIN_CLI_WRAPPER_PATH="$(realpath ./kotlin)"
    xcodebuild -project "$PROJ_DIR" \
        -scheme app \
        -configuration Debug \
        -destination "id=$UDID" \
        -allowProvisioningUpdates \
        DEVELOPMENT_TEAM="$TEAM_ID" \
        CODE_SIGN_STYLE=Automatic \
        build 2>&1 | tail -10

    echo "==> Installing on device..."
    APP_PATH=$(find ~/Library/Developer/Xcode/DerivedData \
        -name "ios-app.app" -path "*/Debug-iphoneos/*" \
        -not -path "*/Index.noindex/*" 2>/dev/null \
        | sort -t/ -k8 -r | head -1)
    if [ -z "$APP_PATH" ]; then
        echo "Error: Could not find .app bundle in DerivedData"
        exit 1
    fi
    echo "    App: $APP_PATH"

    # Sign embedded frameworks with the same identity used for the app
    # Match the signing identity to the team ID so Team IDs are consistent
    SIGN_ID=$(security find-identity -v -p codesigning \
        | grep -v "Created via API" \
        | head -1 | awk -F'"' '{print $2}')
    if [ -z "$SIGN_ID" ]; then
        SIGN_ID=$(security find-identity -v -p codesigning | head -1 | awk -F'"' '{print $2}')
    fi
    echo "    Signing identity: $SIGN_ID"

    # Extract entitlements before re-signing (needed to preserve application-identifier)
    ENTITLEMENTS_FILE=$(mktemp)
    codesign -d --entitlements "$ENTITLEMENTS_FILE" --xml "$APP_PATH" 2>/dev/null
    echo "    Extracted entitlements to $ENTITLEMENTS_FILE"

    for fw in "$APP_PATH"/Frameworks/*.framework; do
        if [ -d "$fw" ]; then
            echo "    Signing $(basename "$fw")..."
            codesign --force --sign "$SIGN_ID" --timestamp=none "$fw"
        fi
    done
    # Re-sign embedded app extensions (PlugIns/) preserving their entitlements
    for ext in "$APP_PATH"/PlugIns/*.appex; do
        if [ -d "$ext" ]; then
            EXT_NAME=$(basename "$ext")
            EXT_ENTITLEMENTS=$(mktemp)
            codesign -d --entitlements "$EXT_ENTITLEMENTS" --xml "$ext" 2>/dev/null || true
            echo "    Signing $EXT_NAME..."
            if [ -s "$EXT_ENTITLEMENTS" ]; then
                codesign --force --sign "$SIGN_ID" --entitlements "$EXT_ENTITLEMENTS" --timestamp=none "$ext"
            else
                codesign --force --sign "$SIGN_ID" --timestamp=none "$ext"
            fi
            rm -f "$EXT_ENTITLEMENTS"
        fi
    done
    # Re-sign the app bundle with original entitlements so the seal covers the updated frameworks
    codesign --force --sign "$SIGN_ID" --entitlements "$ENTITLEMENTS_FILE" --timestamp=none "$APP_PATH"
    rm -f "$ENTITLEMENTS_FILE"

    xcrun devicectl device install app --device "$UDID" "$APP_PATH"

    echo "==> Launching app and attaching to console (Ctrl-C to stop)..."
    xcrun devicectl device process launch --device "$UDID" --console finance.shilling.app

# Launch the signaling server (self-hosted by default, port 8081)
server: ensure-plugin
    #!/usr/bin/env bash
    set -euo pipefail

    if [ -f .env ]; then
        set -a
        source .env
        set +a
    fi

    if [ "${SHILLING_AUTH_MODE:-none}" = "supabase" ]; then
        missing=()
        [ -n "${SHILLING_SUPABASE_URL:-}" ] || missing+=("SHILLING_SUPABASE_URL")
        [ -n "${SHILLING_SUPABASE_ANON_KEY:-}" ] || missing+=("SHILLING_SUPABASE_ANON_KEY")
        [ -n "${SHILLING_SUPABASE_SERVICE_KEY:-}" ] || missing+=("SHILLING_SUPABASE_SERVICE_KEY")
        if [ "${#missing[@]}" -gt 0 ]; then
            echo "Error: hosted Supabase mode is missing required env vars: ${missing[*]}" >&2
            echo "Update .env or export the missing variables, then rerun 'just server'." >&2
            exit 1
        fi
    fi

    ./kotlin run --module server

# Launch server + desktop + iOS simulator for cross-device sync testing
homelab: build-web setup-webrtc ensure-plugin
    #!/usr/bin/env bash
    set -euo pipefail

    # Recursively kill a process and all its descendants
    kill_tree() {
        local pid=$1
        local children
        children=$(pgrep -P "$pid" 2>/dev/null || true)
        for child in $children; do
            kill_tree "$child"
        done
        kill -TERM "$pid" 2>/dev/null || true
    }

    cleanup() {
        echo ""
        echo "Shutting down homelab..."
        [ -n "${SERVER_PID:-}" ] && kill_tree $SERVER_PID
        [ -n "${IOS_PID:-}" ] && kill_tree $IOS_PID
        [ -n "${DESKTOP_PID:-}" ] && kill_tree $DESKTOP_PID
        wait 2>/dev/null
    }
    trap cleanup EXIT INT TERM

    # Start server
    echo "==> Starting server..."
    just server &
    SERVER_PID=$!

    # Wait for server to be ready (includes compile time)
    echo "==> Waiting for server on :8081..."
    for i in $(seq 1 120); do
        if curl -sf http://localhost:8081/api/ice-servers > /dev/null 2>&1; then
            echo "==> Server ready!"
            break
        fi
        if ! kill -0 $SERVER_PID 2>/dev/null; then
            echo "==> Server process died"
            exit 1
        fi
        if [ $i -eq 120 ]; then
            echo "==> Timed out waiting for server (120s)"
            exit 1
        fi
        sleep 1
    done

    # Launch iOS simulator in background
    echo "==> Starting iOS simulator..."
    ./kotlin run ios-app -p iosSimulatorArm64 &
    IOS_PID=$!

    # Launch desktop in foreground
    echo "==> Starting desktop app..."
    cargo tauri dev

# Stream iOS simulator logs (run in a separate terminal)
ios-logs:
    #!/usr/bin/env bash
    DATA_DIR="$(xcrun simctl get_app_container booted finance.shilling.app data 2>/dev/null)"
    if [ -z "$DATA_DIR" ]; then
        echo "Error: finance.shilling.app not installed on booted simulator" >&2
        exit 1
    fi
    LOG_FILE="${DATA_DIR}/tmp/shilling-ios.log"
    : > "$LOG_FILE"
    echo "Tailing $LOG_FILE (truncated) ..."
    tail -f "$LOG_FILE"

# ─── Test ────────────────────────────────────────────────────────────

# Enforce architecture invariants around transport and data flow
guard-architecture:
    ./scripts/enforce-architecture.sh

# Run the architecture guard and JVM tests
test:
    just guard-architecture
    ./scripts/ci/run-jvm-tests.sh

# ─── Dependencies ───────────────────────────────────────────────────

# Install JS dependencies (sql.js, js-joda)
deps:
    npm install

# ─── Utilities ──────────────────────────────────────────────────────

# Show all Amper tasks for a module (e.g. just tasks web-app)
tasks module="":
    #!/usr/bin/env bash
    if [ -z "{{module}}" ]; then
        ./kotlin show tasks
    else
        ./kotlin show tasks 2>&1 | grep ":{{module}}:"
    fi

# Show Amper modules
modules:
    ./kotlin show modules
