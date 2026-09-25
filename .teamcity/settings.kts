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

    // --- Phase 2: release chain and manual platform builds ---
    // The settings VCS root must include refs/tags/*; these trigger filters use
    // the resulting logical names (v*), not the fully qualified Git refs.
    buildType(AndroidBuild)
    buildType(ServerBuild)
    buildType(WebDeploy)
    buildType(SelfHostRelease)
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
            branchFilter = "+:*\n-:v*"
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
// Phase 2a: Android packages
// =============================================================================

object AndroidBuild : BuildType({
    name = "Android Build"
    description = "Build an installable debug APK and unsigned release AAB on Linux"
    artifactRules = "mobile-artifacts/android/** => android.zip"

    vcs {
        root(DslContext.settingsRoot)
    }

    dependencies {
        snapshot(CI) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    steps {
        script {
            name = "Install Android SDK 37"
            scriptContent = "bash scripts/ci/ensure-android-sdk.sh"
        }
        script {
            name = "Package Android APK and AAB"
            scriptContent = "bash scripts/ci/build-android.sh"
        }
    }

    requirements {
        equals("teamcity.agent.jvm.os.name", "Linux")
    }
})

// =============================================================================
// Phase 2b: iOS app archive (macOS agent required)
// =============================================================================

object IosBuild : BuildType({
    name = "iOS Build"
    description = "Archive the iOS app on macOS; export an IPA when signing is configured"
    artifactRules = "mobile-artifacts/ios/** => ios.zip"

    vcs {
        root(DslContext.settingsRoot)
    }

    dependencies {
        snapshot(CI) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    steps {
        script {
            name = "Archive iOS app"
            scriptContent = "bash scripts/ci/build-ios.sh"
        }
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
    description = "Build Linux desktop app (x86_64 AppImage, deb, and rpm)"
    artifactRules = "desktop-artifacts/linux/** => desktop-linux.zip"

    vcs {
        root(DslContext.settingsRoot)
    }

    dependencies {
        snapshot(WebDeploy) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
        artifacts(WebDeploy) {
            buildRule = sameChain()
            artifactRules = "web-app-dist.zip!** => web-app-dist"
        }
    }

    steps {
        script {
            name = "Package Linux desktop app"
            scriptContent = "bash scripts/ci/build-desktop-linux.sh"
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
    name = "Desktop Windows (Linux cross-build)"
    description = "Cross-build the Windows x86_64 NSIS installer on the Linux agent"
    artifactRules = "desktop-artifacts/windows/** => desktop-windows.zip"

    vcs {
        root(DslContext.settingsRoot)
    }

    dependencies {
        snapshot(WebDeploy) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
        artifacts(WebDeploy) {
            buildRule = sameChain()
            artifactRules = "web-app-dist.zip!** => web-app-dist"
        }
    }

    steps {
        script {
            name = "Package Windows desktop app"
            scriptContent = "bash scripts/ci/build-desktop-windows.sh"
        }
    }

    requirements {
        equals("teamcity.agent.jvm.os.name", "Linux")
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
            branchFilter = "+:*\n-:v*"
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
// Phase 2e: Server package (Coolify's hosted deployment remains source-based)
// =============================================================================

object ServerBuild : BuildType({
    name = "Server Build"
    description = "Package the server executable JAR for the self-hosted release"
    artifactRules = "build/tasks/_server_executableJarJvm/server-jvm-executable.jar"

    vcs {
        root(DslContext.settingsRoot)
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

// =============================================================================
// Phase 2f: Publish both self-hosted images from the tested TeamCity artifacts
// =============================================================================

object SelfHostRelease : BuildType({
    name = "Self-hosted Release"
    description = "Publish the web and server images together, then create the GitHub release"

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:v*"
        }
    }

    dependencies {
        snapshot(ServerBuild) {
            onDependencyFailure = FailureAction.FAIL_TO_START
            reuseBuilds = ReuseBuilds.SUCCESSFUL
        }
        artifacts(ServerBuild) {
            buildRule = sameChain()
            artifactRules = "server-jvm-executable.jar => release-input/server"
        }
        snapshot(WebDeploy) {
            onDependencyFailure = FailureAction.FAIL_TO_START
            reuseBuilds = ReuseBuilds.SUCCESSFUL
        }
        artifacts(WebDeploy) {
            buildRule = sameChain()
            artifactRules = "web-app-dist.zip!** => web-app-dist"
        }
    }

    steps {
        script {
            name = "Publish self-hosted release"
            scriptContent = "bash scripts/ci/publish-self-host-release.sh"
        }
    }

    requirements {
        equals("teamcity.agent.jvm.os.name", "Linux")
    }
})
