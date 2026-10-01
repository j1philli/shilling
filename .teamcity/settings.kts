import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.commitStatusPublisher
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.vcs

version = "2024.12"

project {
    description = "Shilling — Kotlin Multiplatform household budgeting app"

    // --- Phase 1: CI (run by the branch build chain) ---
    buildType(CI)

    // --- Phase 2: packages, runtime checks, and deployment ---
    buildType(AndroidBuild)
    buildType(ServerBuild)
    buildType(WebDeploy)
    buildType(RuntimeSmoke)
    buildType(FullRelease)
    buildType(SingleTargetRelease)
    buildType(IosBuild)
    buildType(DesktopLinux)
    buildType(DesktopMacOS)
    buildType(DesktopWindows)
    buildType(LinuxTargets)
    buildType(AllTargets)

    features {
        sharedResource {
            id = "ShillingReleaseLock"
            name = "shilling-release"
            resourceType = infinite()
        }
    }

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
// Phase 1: CI — Architecture guard + tests (Linux agent)
// =============================================================================

object CI : BuildType({
    name = "CI"
    description = "Architecture guard + shared tests + server compile"

    vcs {
        root(DslContext.settingsRoot)
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
            name = "Release workflow checks"
            scriptContent = "python3 -m unittest discover -s scripts/ci/tests -v"
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
    params {
        param("env.BUILD_VCS_BRANCH", "%teamcity.build.branch%")
    }

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
    description = "Build an unsigned macOS desktop DMG from the tested web bundle"
    artifactRules = "desktop-artifacts/macos/** => desktop-macos.zip\nsmoke-results/macos/** => macos-smoke.zip"

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
            name = "Agent health check"
            scriptContent = "bash scripts/ci/check-macos-agent.sh"
        }
        script {
            name = "Package macOS desktop app"
            scriptContent = "bash scripts/ci/build-desktop-macos.sh"
        }
        script {
            name = "Verify and launch packaged DMG"
            scriptContent = "bash scripts/ci/smoke-desktop-macos.sh"
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
// Phase 2d: Web build, runtime checks, and gated Cloudflare Pages deployment
// =============================================================================

object WebDeploy : BuildType({
    // Keep the existing ID so historical builds and artifact dependencies survive.
    name = "Web Build"
    description = "Build the web bundle consumed by desktop, smoke tests, and Pages"
    artifactRules = "web-app-dist/** => web-app-dist.zip"

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

    }

    requirements {
        equals("teamcity.agent.jvm.os.name", "Linux")
    }
})

object RuntimeSmoke : BuildType({
    name = "Web and Server Smoke"
    description = "Run packaged images, browser onboarding, hosted auth, and WebRTC data-channel checks"
    artifactRules = "smoke-results/runtime/** => runtime-smoke.zip"
    vcs { root(DslContext.settingsRoot) }
    dependencies {
        snapshot(ServerBuild) { onDependencyFailure = FailureAction.FAIL_TO_START }
        artifacts(ServerBuild) {
            buildRule = sameChain()
            artifactRules = "server-jvm-executable.jar => release-input/server"
        }
        snapshot(WebDeploy) { onDependencyFailure = FailureAction.FAIL_TO_START }
        artifacts(WebDeploy) {
            buildRule = sameChain()
            artifactRules = "web-app-dist.zip!** => web-app-dist"
        }
    }
    steps {
        script {
            name = "Test packaged web and server images"
            scriptContent = "bash scripts/ci/smoke-runtime.sh"
        }
    }
    failureConditions { executionTimeoutMin = 20 }
    requirements { equals("teamcity.agent.jvm.os.name", "Linux") }
})

// =============================================================================
// Phase 2e: Server package (release promotes its published image to Coolify)
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
// Phase 2f: Two manual release entry points. Main only builds/tests.
// =============================================================================

open class ReleaseBuild(buildId: String, title: String, singleTarget: Boolean) : BuildType({
    id(buildId)
    name = title
    description = "Publish tested packages/images and deploy the selected hosted web and server"
    artifactRules = "release-output/** => release-output.zip"
    maxRunningBuilds = 1
    params {
        param("env.TEAMCITY_BUILD_ID", "%teamcity.build.id%")
        param("env.BUILD_VCS_BRANCH", "%teamcity.build.branch%")
        param("env.SHILLING_RELEASE_MODE", if (singleTarget) "single" else "full")
        if (singleTarget) {
            select("env.SHILLING_RELEASE_TARGET", "web", label = "Target to release",
                display = ParameterDisplay.PROMPT,
                options = listOf("Web (hosted + image)" to "web", "Server (hosted + image)" to "server",
                    "Android" to "android", "iOS" to "ios", "Linux desktop" to "linux",
                    "Windows desktop" to "windows", "macOS desktop" to "macos"))
        }
        checkbox("env.SHILLING_RELEASE_DRY_RUN", "0", label = "Preview only (publish nothing)",
            display = ParameterDisplay.PROMPT, checked = "1", unchecked = "0")
    }
    vcs { root(DslContext.settingsRoot) }
    features { sharedResources { writeLock("shilling-release") } }
    dependencies {
        snapshot(AllTargets) {
            onDependencyFailure = FailureAction.FAIL_TO_START
            onDependencyCancel = FailureAction.CANCEL
            reuseBuilds = ReuseBuilds.SUCCESSFUL
        }
        artifacts(ServerBuild) {
            buildRule = sameChain()
            artifactRules = "server-jvm-executable.jar => release-input/server"
            cleanDestination = true
        }
        artifacts(WebDeploy) {
            buildRule = sameChain()
            artifactRules = "web-app-dist.zip!** => web-app-dist"
            cleanDestination = true
        }
        for ((build, archive, target) in listOf(
            Triple(AndroidBuild, "android.zip", "android"),
            Triple(IosBuild, "ios.zip", "ios"),
            Triple(DesktopLinux, "desktop-linux.zip", "linux"),
            Triple(DesktopWindows, "desktop-windows.zip", "windows"),
            Triple(DesktopMacOS, "desktop-macos.zip", "macos")
        )) {
            artifacts(build) {
                buildRule = sameChain()
                artifactRules = "$archive!** => release-input/clients/$target"
                cleanDestination = true
            }
        }
    }
    steps {
        script {
            name = "Release selected targets"
            scriptContent = "python3 scripts/ci/release.py"
        }
    }
    requirements { equals("teamcity.agent.jvm.os.name", "Linux") }
})

object FullRelease : ReleaseBuild("FullRelease", "Release — Full", false)
object SingleTargetRelease : ReleaseBuild("SingleTargetRelease", "Release — Single Target", true)

// Agentless orchestration jobs keep every package on one source revision.
// Feature branches run the Linux matrix; main runs every target. Release tags
// label already-tested revisions and do not trigger another build or deployment.
object LinuxTargets : BuildType({
    name = "Build Linux-agent targets"
    description = "Build Android, Linux desktop, Windows desktop, web, and server at one revision"
    type = BuildTypeSettings.Type.COMPOSITE

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs {
            branchFilter = "+:*\n-:<default>\n-:main\n-:v*"
        }
    }

    dependencies {
        snapshot(RuntimeSmoke) { onDependencyFailure = FailureAction.FAIL_TO_START }
        snapshot(AndroidBuild) { onDependencyFailure = FailureAction.FAIL_TO_START }
        snapshot(DesktopLinux) { onDependencyFailure = FailureAction.FAIL_TO_START }
        snapshot(DesktopWindows) { onDependencyFailure = FailureAction.FAIL_TO_START }
        snapshot(ServerBuild) { onDependencyFailure = FailureAction.FAIL_TO_START }
    }
})

object AllTargets : BuildType({
    name = "Build all targets"
    description = "Build every target and run available runtime checks at one revision"
    type = BuildTypeSettings.Type.COMPOSITE

    vcs {
        root(DslContext.settingsRoot)
    }

    triggers {
        vcs { branchFilter = "+:<default>\n+:main" }
    }

    dependencies {
        snapshot(LinuxTargets) { onDependencyFailure = FailureAction.FAIL_TO_START }
        snapshot(IosBuild) { onDependencyFailure = FailureAction.FAIL_TO_START }
        snapshot(DesktopMacOS) { onDependencyFailure = FailureAction.FAIL_TO_START }
    }
})
