#!/usr/bin/env bash
# Installs all required tools for a Shilling Linux CI agent.
# Intended for Ubuntu/Debian-based systems (Unraid uses Slackware but most
# CI containers are Ubuntu). Adjust package manager commands if needed.
#
# Run as root or with sudo.
set -euo pipefail

echo "=== Shilling Linux Agent Setup ==="

persist_environment() {
    local rustup_home="$RUSTUP_HOME"
    local cargo_home="$CARGO_HOME"
    local android_home="$ANDROID_HOME"
    local amper_cache="$AMPER_SHARED_CACHES_ROOT"
    local amper_bootstrap_cache="$AMPER_BOOTSTRAP_CACHE_DIR"
    local android_tools="$android_home/cmdline-tools/latest/bin"
    local android_platform_tools="$android_home/platform-tools"
    local profile_file="/etc/profile.d/shilling-ci-agent.sh"
    local env_file="/etc/environment"
    local path_prefix="$cargo_home/bin:$android_tools:$android_platform_tools"
    local agent_props

    cat > "$profile_file" <<EOF
export RUSTUP_HOME="$rustup_home"
export CARGO_HOME="$cargo_home"
export ANDROID_HOME="$android_home"
export ANDROID_SDK_ROOT="$android_home"
export AMPER_SHARED_CACHES_ROOT="$amper_cache"
export AMPER_BOOTSTRAP_CACHE_DIR="$amper_bootstrap_cache"
case ":\$PATH:" in
  *:"$cargo_home/bin":*) ;;
  *) export PATH="$cargo_home/bin:\$PATH" ;;
esac
case ":\$PATH:" in
  *:"$android_tools":*) ;;
  *) export PATH="$android_tools:\$PATH" ;;
esac
case ":\$PATH:" in
  *:"$android_platform_tools":*) ;;
  *) export PATH="$android_platform_tools:\$PATH" ;;
esac
EOF
    chmod 0644 "$profile_file"

    touch "$env_file"
    sed -i '/^RUSTUP_HOME=/d;/^CARGO_HOME=/d;/^ANDROID_HOME=/d;/^ANDROID_SDK_ROOT=/d;/^AMPER_SHARED_CACHES_ROOT=/d;/^AMPER_BOOTSTRAP_CACHE_DIR=/d;/^PATH=/d' "$env_file"
    {
        echo "RUSTUP_HOME=$rustup_home"
        echo "CARGO_HOME=$cargo_home"
        echo "ANDROID_HOME=$android_home"
        echo "ANDROID_SDK_ROOT=$android_home"
        echo "AMPER_SHARED_CACHES_ROOT=$amper_cache"
        echo "AMPER_BOOTSTRAP_CACHE_DIR=$amper_bootstrap_cache"
        echo "PATH=$path_prefix:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    } >> "$env_file"

    for agent_props in \
        /data/teamcity_agent/conf/buildAgent.properties \
        /opt/buildagent/conf/buildAgent.properties
    do
      if [ -f "$agent_props" ]; then
        sed -i '/^env\.RUSTUP_HOME=/d;/^env\.CARGO_HOME=/d;/^env\.ANDROID_HOME=/d;/^env\.ANDROID_SDK_ROOT=/d;/^env\.AMPER_SHARED_CACHES_ROOT=/d;/^env\.AMPER_BOOTSTRAP_CACHE_DIR=/d;/^env\.PATH=/d' "$agent_props"
        {
            echo "env.RUSTUP_HOME=$rustup_home"
            echo "env.CARGO_HOME=$cargo_home"
            echo "env.ANDROID_HOME=$android_home"
            echo "env.ANDROID_SDK_ROOT=$android_home"
            echo "env.AMPER_SHARED_CACHES_ROOT=$amper_cache"
            echo "env.AMPER_BOOTSTRAP_CACHE_DIR=$amper_bootstrap_cache"
            echo "env.PATH=$path_prefix:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        } >> "$agent_props"
      fi
    done
}

link_amper_cache() {
    local home_dir="$1"
    local jetbrains_cache="$home_dir/.cache/JetBrains"
    local amper_link="$jetbrains_cache/Amper"
    local backup_path

    [ -d "$home_dir" ] || return 0

    mkdir -p "$jetbrains_cache"
    if [ -L "$amper_link" ]; then
        ln -sfn "$AMPER_SHARED_CACHES_ROOT" "$amper_link"
    elif [ -e "$amper_link" ]; then
        backup_path="$amper_link.stale.$(date +%Y%m%d%H%M%S)"
        echo "Moving existing Amper cache aside: $amper_link -> $backup_path"
        mv "$amper_link" "$backup_path"
        ln -s "$AMPER_SHARED_CACHES_ROOT" "$amper_link"
    else
        ln -s "$AMPER_SHARED_CACHES_ROOT" "$amper_link"
    fi

    if id buildagent &>/dev/null; then
        chown -h buildagent:buildagent "$amper_link" || true
        chown -R buildagent:buildagent "$jetbrains_cache" || true
    fi
}

INSTALL_ROOT="${SHILLING_CI_INSTALL_ROOT:-/opt/shilling-ci}"
mkdir -p "$INSTALL_ROOT"

ANDROID_HOME="${SHILLING_CI_ANDROID_HOME:-$INSTALL_ROOT/android-sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export RUSTUP_HOME="${SHILLING_CI_RUSTUP_HOME:-$INSTALL_ROOT/rustup}"
export CARGO_HOME="${SHILLING_CI_CARGO_HOME:-$INSTALL_ROOT/cargo}"
export AMPER_SHARED_CACHES_ROOT="${SHILLING_CI_AMPER_CACHE:-$INSTALL_ROOT/amper-cache}"
export AMPER_BOOTSTRAP_CACHE_DIR="${SHILLING_CI_AMPER_BOOTSTRAP_CACHE:-$INSTALL_ROOT/amper-bootstrap}"
export PATH="$CARGO_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$AMPER_SHARED_CACHES_ROOT" "$AMPER_BOOTSTRAP_CACHE_DIR"

# ---------- System packages ----------

apt-get update -qq

# JDK 21
if ! java -version 2>&1 | grep -q '"2[1-9]\.' ; then
    echo "Installing JDK 21..."
    apt-get install -y -qq openjdk-21-jdk
else
    echo "JDK 21+ already installed"
fi

# Node.js 22 LTS (via NodeSource)
if ! command -v node &>/dev/null || [ "$(node --version | sed 's/v\([0-9]*\).*/\1/')" -lt 18 ]; then
    echo "Installing Node.js 22..."
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y -qq nodejs
else
    echo "Node.js $(node --version) already installed"
fi

# ripgrep (used by architecture guard)
if ! command -v rg &>/dev/null; then
    echo "Installing ripgrep..."
    apt-get install -y -qq ripgrep
else
    echo "ripgrep already installed"
fi

# Docker
if ! command -v docker &>/dev/null; then
    echo "Installing Docker..."
    curl -fsSL https://get.docker.com | sh
else
    echo "Docker already installed"
fi

# Tauri system dependencies (WebKitGTK, etc.)
echo "Installing Tauri system dependencies..."
apt-get install -y -qq \
    libwebkit2gtk-4.1-dev \
    build-essential \
    curl \
    wget \
    file \
    libxdo-dev \
    libssl-dev \
    libayatana-appindicator3-dev \
    librsvg2-dev \
    unzip

# ---------- Rust ----------

if [ ! -x "$CARGO_HOME/bin/cargo" ]; then
    echo "Installing Rust..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
else
    echo "Rust already installed"
fi

# ---------- just ----------

if ! command -v just &>/dev/null; then
    echo "Installing just..."
    "$CARGO_HOME/bin/cargo" install just --locked
else
    echo "just already installed"
fi

# ---------- GitHub CLI ----------

if ! command -v gh &>/dev/null; then
    echo "Installing GitHub CLI..."
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        | tee /etc/apt/sources.list.d/github-cli.list > /dev/null
    apt-get update -qq
    apt-get install -y -qq gh
else
    echo "GitHub CLI already installed"
fi

# ---------- Cloudflare Wrangler ----------

if ! command -v wrangler &>/dev/null; then
    echo "Installing Wrangler..."
    npm install -g wrangler
else
    echo "Wrangler already installed"
fi

# ---------- Android SDK ----------

if [ ! -d "$ANDROID_HOME/platforms/android-36" ] || [ ! -d "$ANDROID_HOME/platforms/android-37" ]; then
    echo "Installing Android SDK..."
    mkdir -p "$ANDROID_HOME"

    # Download command-line tools if not present
    if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
        CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        curl -fsSL "$CMDLINE_TOOLS_URL" -o /tmp/cmdline-tools.zip
        unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-tmp
        mkdir -p "$ANDROID_HOME/cmdline-tools"
        mv /tmp/cmdline-tools-tmp/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
        rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-tmp
    fi

    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

    yes | sdkmanager --licenses > /dev/null 2>&1 || true
    sdkmanager "platforms;android-36" "platforms;android-37" "build-tools;36.0.0" "platform-tools"
else
    echo "Android SDK (android-36 and android-37) already installed"
fi

persist_environment

chmod -R a+rX "$INSTALL_ROOT"
if id buildagent &>/dev/null; then
    chown -R buildagent:buildagent "$INSTALL_ROOT"
fi

for cache_home in "$HOME" /home/buildagent /root; do
    link_amper_cache "$cache_home"
done

# ---------- Done ----------

echo ""
echo "=== Setup complete. Run check-linux-agent.sh to verify. ==="
