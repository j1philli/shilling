import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.commitStatusPublisher
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.vcs
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger

version = "2024.12"

project {
    description = "Shilling — Kotlin Multiplatform household budgeting app"

    // --- Phase 1: CI (every push) ---
    buildType(CI)

    // --- Phase 2: Platform builds (tags + manual) ---
    // The settings VCS root must include refs/tags/*; these trigger filters use
    // the resulting logical names (v*), not the fully qualified Git refs.
    buildType(AndroidBuild)
    buildType(ServerBuild)
    buildType(WebDeploy)
    buildType(IosBuild)
    buildType(DesktopLinux)
    buildType(DesktopMacOS)
    buildType(DesktopWindows)

    // --- Parameters (secrets configured in TeamCity UI) ---
    params {
        password("env.GITHUB_TOKEN", "credentialsJSON:github-token", display = ParameterDisplay.HIDDEN)
        password("env.PLAY_SERVICE_ACCOUNT_JSON", "credentialsJSON:play-service-account", display = ParameterDisplay.HIDDEN)
        password("env.ASC_API_KEY", "credentialsJSON:asc-api-key", display = ParameterDisplay.HIDDEN)
        param("env.CLOUDFLARE_PAGES_PROJECT", "shilling-app")
        param("env.CLOUDFLARE_PAGES_DOMAIN", "app.shilling.finance")
        param("env.AMPER_SHARED_CACHES_ROOT", "/opt/shilling-ci/amper-cache")
        param("env.AMPER_BOOTSTRAP_CACHE_DIR", "/opt/shilling-ci/amper-bootstrap")
        param("env.CI_RETRY_ATTEMPTS", "5")
        param("env.CI_RETRY_DELAY_SECONDS", "120")
    }
}

// =============================================================================
// Phase 1: CI — Architecture guard + tests (every push, Linux agent)
// =============================================================================

object CI : BuildType({
    name = "CI"
    description = "Architecture guard + shared tests + server compile"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:*"
        }
    }

    steps {
        script {
            name = "Agent health check"
            scriptContent = "bash scripts/ci/check-linux-agent.sh"
        }
        script {
            name = "Install npm dependencies"
            scriptContent = "npm install"
        }
        script {
            name = "Architecture guard"
            scriptContent = "bash scripts/enforce-architecture.sh"
        }
        script {
            name = "Product version guard"
            scriptContent = "python3 scripts/ci/check-version.py"
        }
        script {
            name = "Run tests"
            scriptContent = "bash scripts/ci/run-jvm-tests.sh"
        }
        script {
            name = "Compile server"
            scriptContent = "bash scripts/ci/retry.sh ./kotlin build -m server"
        }
    }

    features {
        commitStatusPublisher {
            publisher = github {
                githubUrl = "https://api.github.com"
                authType = personalToken {
                    token = "%env.GITHUB_TOKEN%"
                }
            }
        }
    }

    requirements {
        equals("teamcity.agent.jvm.os.name", "Linux")
    }
})

// =============================================================================
// Phase 2a: Android Build + Play Store Internal Testing
// =============================================================================

object AndroidBuild : BuildType({
    name = "Android Build"
    description = "Build AAB, sign, upload to Play Internal Testing"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:v*"
        }
    }

    dependencies {
        snapshot(CI) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    steps {
        script {
            name = "Build Android AAB"
            scriptContent = """
                bash scripts/ci/retry.sh ./kotlin build -m android-app
            """.trimIndent()
        }
        script {
            name = "Sign AAB"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail

                AAB=$(find build -name "*.aab" -type f | head -1)
                if [ -z "${'$'}AAB" ]; then
                    echo "ERROR: No AAB found"
                    exit 1
                fi
                echo "Found AAB: ${'$'}AAB"

                # Decode keystore from TeamCity parameter
                echo "${'$'}PLAY_KEYSTORE_BASE64" | base64 -d > /tmp/upload-keystore.jks

                jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
                    -keystore /tmp/upload-keystore.jks \
                    -storepass "${'$'}PLAY_KEYSTORE_PASSWORD" \
                    -keypass "${'$'}PLAY_KEY_PASSWORD" \
                    "${'$'}AAB" "${'$'}PLAY_KEY_ALIAS"

                rm -f /tmp/upload-keystore.jks
                echo "Signed: ${'$'}AAB"
            """.trimIndent()
        }
        script {
            name = "Upload to Play Internal Testing"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail

                AAB=$(find build -name "*.aab" -type f | head -1)

                # Write service account JSON
                echo "${'$'}PLAY_SERVICE_ACCOUNT_JSON" > /tmp/play-sa.json

                # Use bundletool or Google Play API via curl
                # For now, use Fastlane supply if available, else manual upload
                if command -v fastlane &>/dev/null; then
                    fastlane supply \
                        --aab "${'$'}AAB" \
                        --track internal \
                        --package_name finance.shilling.android \
                        --json_key /tmp/play-sa.json
                else
                    echo "Fastlane not found. Upload AAB manually or install Fastlane."
                    echo "AAB path: ${'$'}AAB"
                fi

                rm -f /tmp/play-sa.json
            """.trimIndent()
        }
    }

    params {
        password("env.PLAY_KEYSTORE_BASE64", "credentialsJSON:play-keystore-base64", display = ParameterDisplay.HIDDEN)
        password("env.PLAY_KEYSTORE_PASSWORD", "credentialsJSON:play-keystore-password", display = ParameterDisplay.HIDDEN)
        password("env.PLAY_KEY_PASSWORD", "credentialsJSON:play-key-password", display = ParameterDisplay.HIDDEN)
        param("env.PLAY_KEY_ALIAS", "upload")
    }

    requirements {
        equals("teamcity.agent.jvm.os.name", "Linux")
    }
})

// =============================================================================
// Phase 2b: iOS Build + TestFlight
// =============================================================================

object IosBuild : BuildType({
    name = "iOS Build"
    description = "Build iOS app, archive, upload to TestFlight"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:v*"
        }
    }

    dependencies {
        snapshot(CI) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    steps {
        script {
            name = "Agent health check"
            scriptContent = "bash scripts/ci/check-macos-agent.sh"
        }
        script {
            name = "Download WebRTC framework"
            scriptContent = "bash setup-webrtc.sh"
        }
        script {
            name = "Build Kotlin framework"
            scriptContent = "bash scripts/ci/retry.sh ./kotlin build -m ios-app"
        }
        script {
            name = "Archive and export IPA"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail

                XCODEPROJ="app/ios-app/module.xcodeproj"
                SCHEME="ios-app"
                ARCHIVE_PATH="build/ios/Shilling.xcarchive"
                EXPORT_PATH="build/ios/export"

                xcodebuild archive \
                    -project "${'$'}XCODEPROJ" \
                    -scheme "${'$'}SCHEME" \
                    -archivePath "${'$'}ARCHIVE_PATH" \
                    -destination "generic/platform=iOS" \
                    CODE_SIGN_STYLE=Manual \
                    | xcpretty || true

                # Create export options plist
                cat > /tmp/ExportOptions.plist << 'PLIST'
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>method</key>
                    <string>app-store-connect</string>
                    <key>destination</key>
                    <string>upload</string>
                    <key>signingStyle</key>
                    <string>manual</string>
                    <key>teamID</key>
                    <string>7QN6HR273V</string>
                </dict>
                </plist>
                PLIST

                xcodebuild -exportArchive \
                    -archivePath "${'$'}ARCHIVE_PATH" \
                    -exportOptionsPlist /tmp/ExportOptions.plist \
                    -exportPath "${'$'}EXPORT_PATH"
            """.trimIndent()
        }
        script {
            name = "Upload to TestFlight"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail

                IPA=$(find build/ios/export -name "*.ipa" -type f | head -1)
                if [ -z "${'$'}IPA" ]; then
                    echo "ERROR: No IPA found"
                    exit 1
                fi

                xcrun altool --upload-app \
                    --type ios \
                    --file "${'$'}IPA" \
                    --apiKey "${'$'}ASC_API_KEY_ID" \
                    --apiIssuer "${'$'}ASC_API_ISSUER_ID"
            """.trimIndent()
        }
    }

    params {
        param("env.ASC_API_KEY_ID", "")
        param("env.ASC_API_ISSUER_ID", "")
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Mac")
    }
})

// =============================================================================
// Phase 2c: Desktop Builds
// =============================================================================

object DesktopLinux : BuildType({
    name = "Desktop Linux"
    description = "Build Linux desktop app (x86_64 AppImage)"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:v*"
        }
    }

    dependencies {
        snapshot(CI) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    steps {
        script {
            name = "Install npm dependencies"
            scriptContent = "npm install"
        }
        script {
            name = "Build wasmJs artifacts"
            scriptContent = "bash build-web.sh"
        }
        script {
            name = "Build Tauri app"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                cd src-tauri
                cargo tauri build --bundles appimage
            """.trimIndent()
        }
        script {
            name = "Upload to GitHub Release"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail

                TAG="${'$'}{BUILD_VCS_BRANCH##refs/tags/}"
                echo "Uploading artifacts for tag: ${'$'}TAG"

                # Find built artifacts
                BUNDLE_DIR="src-tauri/target/release/bundle"

                for f in "${'$'}BUNDLE_DIR"/appimage/*.AppImage; do
                    if [ -f "${'$'}f" ]; then
                        echo "Uploading: ${'$'}f"
                        gh release upload "${'$'}TAG" "${'$'}f" --clobber || true
                    fi
                done
            """.trimIndent()
        }
    }

    requirements {
        equals("teamcity.agent.jvm.os.name", "Linux")
    }
})

object DesktopMacOS : BuildType({
    name = "Desktop macOS"
    description = "Build macOS desktop app (.dmg) with notarization"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:v*"
        }
    }

    dependencies {
        snapshot(CI) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    steps {
        script {
            name = "Agent health check"
            scriptContent = "bash scripts/ci/check-macos-agent.sh"
        }
        script {
            name = "Install npm dependencies"
            scriptContent = "npm install"
        }
        script {
            name = "Build wasmJs artifacts"
            scriptContent = "bash build-web.sh"
        }
        script {
            name = "Build Tauri app (universal binary)"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                cd src-tauri

                # Build for both architectures if possible
                if rustup target list --installed | grep -q "aarch64-apple-darwin"; then
                    cargo tauri build --target aarch64-apple-darwin
                fi
                if rustup target list --installed | grep -q "x86_64-apple-darwin"; then
                    cargo tauri build --target x86_64-apple-darwin
                fi

                # If universal binary target is available
                if rustup target list --installed | grep -q "universal-apple-darwin"; then
                    cargo tauri build --target universal-apple-darwin
                fi
            """.trimIndent()
        }
        script {
            name = "Upload to GitHub Release"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail

                TAG="${'$'}{BUILD_VCS_BRANCH##refs/tags/}"
                echo "Uploading macOS artifacts for tag: ${'$'}TAG"

                BUNDLE_DIR="src-tauri/target"

                # Upload .dmg files from any target directory
                find "${'$'}BUNDLE_DIR" -name "*.dmg" -type f | while read -r f; do
                    echo "Uploading: ${'$'}f"
                    gh release upload "${'$'}TAG" "${'$'}f" --clobber || true
                done
            """.trimIndent()
        }
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Mac")
    }
})

object DesktopWindows : BuildType({
    name = "Desktop Windows"
    description = "Build Windows desktop app (.msi + .exe)"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:v*"
        }
    }

    dependencies {
        snapshot(CI) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    steps {
        script {
            name = "Agent health check"
            scriptContent = "powershell -ExecutionPolicy Bypass -File scripts/ci/check-windows-agent.ps1"
        }
        script {
            name = "Install npm dependencies"
            scriptContent = "npm install"
        }
        script {
            name = "Build wasmJs artifacts"
            scriptContent = "bash build-web.sh"
        }
        script {
            name = "Build Tauri app"
            scriptContent = """
                cd src-tauri
                cargo tauri build
            """.trimIndent()
        }
        script {
            name = "Upload to GitHub Release"
            scriptContent = """
                set TAG=%BUILD_VCS_BRANCH:refs/tags/=%
                echo Uploading Windows artifacts for tag: %TAG%

                for %%f in (src-tauri\target\release\bundle\msi\*.msi) do (
                    echo Uploading: %%f
                    gh release upload %TAG% "%%f" --clobber
                )
                for %%f in (src-tauri\target\release\bundle\nsis\*.exe) do (
                    echo Uploading: %%f
                    gh release upload %TAG% "%%f" --clobber
                )
            """.trimIndent()
        }
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Windows")
    }
})

// =============================================================================
// Phase 2d: Web Deploy to Cloudflare Pages
// =============================================================================

object WebDeploy : BuildType({
    name = "Web Deploy"
    description = "Build wasmJs and deploy to Cloudflare Pages"
    artifactRules = "web-app-dist/** => web-app-dist.zip"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:*"
        }
    }

    steps {
        script {
            name = "Agent health check"
            scriptContent = "bash scripts/ci/check-linux-web-agent.sh"
        }
        script {
            name = "Install npm dependencies"
            scriptContent = "npm ci"
        }
        script {
            name = "Prepare SQLDelight plugin"
            scriptContent = "just ensure-plugin"
        }
        script {
            name = "Build wasmJs artifacts"
            scriptContent = "bash build-web.sh"
        }
        script {
            name = "Deploy to Cloudflare Pages"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail

                # Pages rejects any individual asset above 25 MiB.
                WASM_SIZE=$(wc -c < web-app-dist/web-app.wasm)
                if [ "${'$'}WASM_SIZE" -gt 26214400 ]; then
                    echo "ERROR: web-app.wasm is ${'$'}WASM_SIZE bytes; Cloudflare Pages allows at most 25 MiB per asset"
                    exit 1
                fi

                DEPLOY_BRANCH="${'$'}{BUILD_VCS_BRANCH:-}"
                if [ -z "${'$'}DEPLOY_BRANCH" ]; then
                    DEPLOY_BRANCH="$(git branch --show-current 2>/dev/null || true)"
                fi
                if [ -z "${'$'}DEPLOY_BRANCH" ]; then
                    DEPLOY_BRANCH="$(git name-rev --name-only HEAD 2>/dev/null | sed 's#^remotes/origin/##' || true)"
                fi
                if [ -z "${'$'}DEPLOY_BRANCH" ] || [ "${'$'}DEPLOY_BRANCH" = "undefined" ]; then
                    echo "ERROR: Could not determine checkout branch for Cloudflare Pages deploy"
                    exit 1
                fi

                case "${'$'}DEPLOY_BRANCH" in
                    refs/heads/main|main)
                        ;;
                    *)
                        echo "Skipping Cloudflare Pages deploy for branch ${'$'}DEPLOY_BRANCH"
                        exit 0
                        ;;
                esac

                if [ -z "${'$'}{CLOUDFLARE_API_TOKEN:-}" ] && [ -n "${'$'}{CF_API_TOKEN:-}" ]; then
                    export CLOUDFLARE_API_TOKEN="${'$'}CF_API_TOKEN"
                fi

                if [ -z "${'$'}{CLOUDFLARE_API_TOKEN:-}" ]; then
                    echo "ERROR: CLOUDFLARE_API_TOKEN is not set"
                    exit 1
                fi

                if [ -z "${'$'}{CLOUDFLARE_ACCOUNT_ID:-}" ]; then
                    echo "ERROR: CLOUDFLARE_ACCOUNT_ID is not set"
                    echo "Set TeamCity parameter env.CLOUDFLARE_ACCOUNT_ID to the Cloudflare account ID for the Pages project."
                    exit 1
                fi

                if [ -z "${'$'}{CLOUDFLARE_PAGES_PROJECT:-}" ]; then
                    echo "ERROR: CLOUDFLARE_PAGES_PROJECT is not set"
                    echo "Set TeamCity parameter env.CLOUDFLARE_PAGES_PROJECT to the Cloudflare Pages project name."
                    exit 1
                fi

                unset CF_API_TOKEN

                echo "Deploying Cloudflare Pages project ${'$'}CLOUDFLARE_PAGES_PROJECT in account ${'$'}CLOUDFLARE_ACCOUNT_ID"

                npx wrangler pages deploy web-app-dist/ \
                    --project-name="${'$'}CLOUDFLARE_PAGES_PROJECT" \
                    --branch=main

                node scripts/ci/ensure-pages-domain.mjs
            """.trimIndent()
        }
    }

    requirements {
        equals("teamcity.agent.jvm.os.name", "Linux")
    }
})

// =============================================================================
// Phase 2e: Server package validation (deployment is handled by Coolify)
// =============================================================================

object ServerBuild : BuildType({
    name = "Server Build"
    description = "Package the server executable JAR; Coolify builds and deploys from Git"
    artifactRules = "build/tasks/_server_executableJarJvm/server-jvm-executable.jar"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:v*"
        }
    }

    dependencies {
        snapshot(CI) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    steps {
        script {
            name = "Architecture guard"
            scriptContent = "bash scripts/enforce-architecture.sh"
        }
        script {
            name = "Package server executable JAR"
            scriptContent = "bash scripts/ci/retry.sh ./kotlin package -m server -f executable-jar"
        }
    }

    requirements {
        equals("teamcity.agent.jvm.os.name", "Linux")
    }
})
