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

if ! command -v node &>/dev/null || [ "$(node --version | sed 's/v\([0-9]*\).*/\1/')" -lt 18 ]; then
    echo "Installing Node.js..."
    brew install node
else
    echo "Node.js $(node --version) already installed"
fi

# ---------- Rust ----------

if ! command -v cargo &>/dev/null; then
    echo "Installing Rust..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source "$HOME/.cargo/env"
else
    echo "Rust already installed"
fi

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

# ---------- ImageMagick ----------

if ! command -v magick &>/dev/null; then
    echo "Installing ImageMagick..."
    brew install imagemagick
else
    echo "ImageMagick already installed"
fi

# ---------- GitHub CLI ----------

if ! command -v gh &>/dev/null; then
    echo "Installing GitHub CLI..."
    brew install gh
else
    echo "GitHub CLI already installed"
fi

# ---------- xcpretty (nicer xcodebuild output) ----------

if ! command -v xcpretty &>/dev/null; then
    echo "Installing xcpretty..."
    gem install xcpretty 2>/dev/null || sudo gem install xcpretty
else
    echo "xcpretty already installed"
fi

# ---------- Done ----------

echo ""
echo "=== Setup complete. Run check-macos-agent.sh to verify. ==="
echo ""
echo "Manual steps remaining:"
echo "  1. Install Apple distribution certificate + provisioning profile in Keychain"
echo "  2. Configure App Store Connect API key (store as TeamCity parameter)"
