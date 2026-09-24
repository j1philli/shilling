# Installs all required tools for a Shilling Windows CI agent.
# Uses winget (App Installer) and scoop as fallback.
# Run as Administrator.

Write-Host "=== Shilling Windows Agent Setup ==="

$ErrorActionPreference = "Stop"

function Install-WithWinget {
    param(
        [string]$Name,
        [string]$PackageId,
        [string]$TestCommand
    )

    if (Get-Command $TestCommand -ErrorAction SilentlyContinue) {
        Write-Host "$Name already installed"
        return
    }

    Write-Host "Installing $Name..."
    winget install --id $PackageId --accept-package-agreements --accept-source-agreements --silent
}

# ---------- JDK 21 ----------

$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Host "Installing JDK 21..."
    winget install --id Microsoft.OpenJDK.21 --accept-package-agreements --accept-source-agreements --silent
} else {
    $verOutput = & java -version 2>&1 | Select-Object -First 1
    if ($verOutput -match '"(\d+)') {
        $ver = [int]$Matches[1]
        if ($ver -lt 21) {
            Write-Host "Upgrading JDK to 21..."
            winget install --id Microsoft.OpenJDK.21 --accept-package-agreements --accept-source-agreements --silent
        } else {
            Write-Host "JDK $ver already installed"
        }
    }
}

# ---------- Node.js ----------

$nodeCmd = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCmd) {
    Write-Host "Installing Node.js..."
    winget install --id OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements --silent
} else {
    $ver = (& node --version) -replace 'v(\d+).*', '$1'
    if ([int]$ver -lt 18) {
        Write-Host "Upgrading Node.js..."
        winget install --id OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements --silent
    } else {
        Write-Host "Node.js v$ver already installed"
    }
}

# ---------- Rust ----------

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    Write-Host "Installing Rust..."
    Invoke-WebRequest -Uri "https://win.rustup.rs/x86_64" -OutFile "$env:TEMP\rustup-init.exe"
    & "$env:TEMP\rustup-init.exe" -y --default-toolchain stable
    Remove-Item "$env:TEMP\rustup-init.exe"
    # Refresh PATH
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")
} else {
    Write-Host "Rust already installed"
}

# ---------- cargo-tauri ----------

if (-not (Get-Command cargo-tauri -ErrorAction SilentlyContinue)) {
    Write-Host "Installing cargo-tauri..."
    cargo install tauri-cli
} else {
    Write-Host "cargo-tauri already installed"
}

# ---------- GitHub CLI ----------

Install-WithWinget "GitHub CLI" "GitHub.cli" "gh"

# ---------- Done ----------

Write-Host ""
Write-Host "=== Setup complete. Run check-windows-agent.ps1 to verify. ==="
Write-Host ""
Write-Host "Manual steps remaining:"
Write-Host "  1. Import code signing certificate (.pfx) into CurrentUser certificate store"
Write-Host "  2. Set certificate thumbprint as TeamCity parameter"
