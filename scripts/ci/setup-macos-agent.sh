#!/usr/bin/env bash
# Installs all required tools for a Shilling macOS CI agent.
# Uses Homebrew. Run as the CI agent user (not root).
set -euo pipefail

echo "=== Shilling macOS Agent Setup ==="

# ---------- Homebrew ----------

if ! command -v brew &>/dev/null; then
    echo "Installing Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

    # Add to PATH for Apple Silicon
    if [ -f /opt/homebrew/bin/brew ]; then
        eval "$(/opt/homebrew/bin/brew shellenv)"
    fi
else
    echo "Homebrew already installed"
fi

# ---------- JDK 21 ----------

if ! java -version 2>&1 | grep -q '"2[1-9]\.' ; then
    echo "Installing JDK 21..."
    brew install openjdk@21
    sudo ln -sfn "$(brew --prefix openjdk@21)/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
else
    echo "JDK 21+ already installed"
fi

# ---------- Xcode ----------

if ! command -v xcodebuild &>/dev/null; then
    echo "ERROR: Xcode must be installed from the App Store or Apple Developer site."
    echo "       Install Xcode 15+, then run: sudo xcode-select -s /Applications/Xcode.app"
    exit 1
else
    echo "Xcode $(xcodebuild -version | head -1) already installed"
fi

# Accept Xcode license (idempotent)
sudo xcodebuild -license accept 2>/dev/null || true

# ---------- Node.js ----------

if ! command -v node &>/dev/null || ! node "$(dirname "${BASH_SOURCE[0]}")/check-node-version.cjs"; then
    echo "Installing a patched Node.js LTS release..."
    if brew list --versions node@22 >/dev/null 2>&1; then
        brew upgrade node@22
    else
        brew install node@22
    fi
    export PATH="$(brew --prefix node@22)/bin:$PATH"
    echo "Add $(brew --prefix node@22)/bin to the CI agent PATH."
fi
node "$(dirname "${BASH_SOURCE[0]}")/check-node-version.cjs"

export JAVA_HOME="$(bash "$(dirname "${BASH_SOURCE[0]}")/setup-java.sh)"
export PATH="$JAVA_HOME/bin:$PATH"
node "$(dirname "${BASH_SOURCE[0]}")/check-java-version.cjs"

# ---------- Rust ----------

if ! command -v rustup &>/dev/null; then
    echo "Installing rustup..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
else
    echo "rustup already installed"
fi
export PATH="$HOME/.cargo/bin:$PATH"
rustup toolchain install stable --profile minimal
rustup default stable

# Add both macOS targets for universal builds
rustup target add aarch64-apple-darwin 2>/dev/null || true
rustup target add x86_64-apple-darwin 2>/dev/null || true

# ---------- cargo-tauri ----------

if ! command -v cargo-tauri &>/dev/null; then
    echo "Installing cargo-tauri..."
    cargo install tauri-cli
else
    echo "cargo-tauri already installed"
fi

# ---------- Done ----------

echo ""
echo "=== Setup complete. Run check-macos-agent.sh to verify. ==="
echo ""
echo "Manual steps remaining:"
echo "  1. Install Apple distribution certificate + provisioning profile in Keychain"
echo "  2. Configure App Store Connect API key (store as TeamCity parameter)"
