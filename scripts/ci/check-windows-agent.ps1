# Verifies a Windows CI agent has all required tools for Shilling builds.
# Exit non-zero with clear messages for any missing/incompatible tool.

$fail = 0

function Check-Command {
    param(
        [string]$Name,
        [string]$Command
    )
    $cmd = Get-Command $Command -ErrorAction SilentlyContinue
    if (-not $cmd) {
        Write-Host "FAIL: $Name - '$Command' not found"
        $script:fail = 1
    } else {
        Write-Host "  OK: $Name - $($cmd.Source)"
    }
}

function Check-Java {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $cmd) {
        Write-Host "FAIL: JDK 21+ - 'java' not found"
        $script:fail = 1
        return
    }
    $verOutput = & java -version 2>&1 | Select-Object -First 1
    if ($verOutput -match '"(\d+)') {
        $ver = [int]$Matches[1]
        if ($ver -lt 21) {
            Write-Host "FAIL: JDK 21+ - found JDK $ver"
            $script:fail = 1
        } else {
            Write-Host "  OK: JDK - version $ver"
        }
    } else {
        Write-Host "WARN: JDK - could not parse version from: $verOutput"
    }
}

function Check-Node {
    $cmd = Get-Command node -ErrorAction SilentlyContinue
    if (-not $cmd) {
        Write-Host "FAIL: Node.js 18+ - 'node' not found"
        $script:fail = 1
        return
    }
    $ver = (& node --version) -replace 'v(\d+).*', '$1'
    if ([int]$ver -lt 18) {
        Write-Host "FAIL: Node.js 18+ - found Node $ver"
        $script:fail = 1
    } else {
        Write-Host "  OK: Node.js - v$ver"
    }
}

function Check-Signing {
    $certs = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert -ErrorAction SilentlyContinue
    if ($certs -and $certs.Count -gt 0) {
        Write-Host "  OK: Code signing - $($certs.Count) certificate(s) found"
    } else {
        Write-Host "WARN: Code signing - no code signing certificates found in CurrentUser store"
    }
}

function Check-Kotlin CLI {
    param([string]$Root)
    $kotlinPath = Join-Path $Root "kotlin"
    # On Windows, check for kotlin.bat or kotlin.cmd as well
    $found = (Test-Path $kotlinPath) -or (Test-Path "$kotlinPath.bat") -or (Test-Path "$kotlinPath.cmd")
    if (-not $found) {
        Write-Host "FAIL: Kotlin CLI - kotlin script not found in $Root"
        $script:fail = 1
    } else {
        Write-Host "  OK: Kotlin CLI - found in $Root"
    }
}

Write-Host "=== Shilling Windows Agent Health Check ==="
Write-Host ""

Check-Java
Check-Node
Check-Command "npm"         "npm"
Check-Command "Rust/cargo"  "cargo"
Check-Command "cargo-tauri" "cargo-tauri"
Check-Command "gh CLI"      "gh"

Write-Host ""
Write-Host "--- Signing ---"
Check-Signing

Write-Host ""
Write-Host "--- Project ---"
$rootDir = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
Check-Kotlin CLI $rootDir

Write-Host ""
if ($fail -ne 0) {
    Write-Host "RESULT: Some checks failed. Install missing tools before running CI."
    exit 1
}
Write-Host "RESULT: All checks passed."
