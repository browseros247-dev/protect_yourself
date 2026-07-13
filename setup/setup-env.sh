#!/usr/bin/env bash
#
# setup-env.sh — one-shot development environment setup for Protect-Yourself
#
# This script recreates the full Android build environment from scratch:
#   1. Downloads and extracts JDK 17 (Temurin 17.0.13+11)
#   2. Downloads and extracts Android cmdline-tools
#   3. Accepts all Android SDK licenses
#   4. Installs Android SDK platform 35, build-tools 35.0.0, platform-tools
#   5. Creates local.properties pointing to the SDK
#   6. Verifies the setup by running `gradlew help`
#
# Usage:
#   cd protect_yourself/
#   chmod +x setup/setup-env.sh
#   ./setup/setup-env.sh
#
# Options:
#   --install-dir <path>   Base directory for JDK + SDK (default: /home/z/my-project)
#   --skip-jdk             Skip JDK download (use existing JAVA_HOME)
#   --skip-sdk             Skip SDK download (use existing ANDROID_HOME)
#   --help                 Show this help message
#
# The script is IDEMPOTENT — it skips downloads that already exist and
# re-checks installations. Safe to re-run any time.
#
# Tested on: Debian 13 (x86_64), Ubuntu 22.04+. Requires curl + unzip + tar.
#
set -euo pipefail

# ============================================================================
# Configuration — edit these if you need different versions
# ============================================================================

# JDK 17 (Temurin) — required for Kotlin 2.0.21 + AGP 8.7.2
JDK_VERSION="jdk-17.0.13+11"
JDK_DOWNLOAD_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz"
JDK_DIR_NAME="jdk-17.0.13+11"

# Android cmdline-tools — required to install SDK packages
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

# Android SDK packages to install (one per line)
SDK_PACKAGES=(
    "platforms;android-35"
    "build-tools;35.0.0"
    "platform-tools"
)

# Defaults
INSTALL_DIR="/home/z/my-project"
SKIP_JDK=false
SKIP_SDK=false

# ============================================================================
# Parse arguments
# ============================================================================

while [[ $# -gt 0 ]]; do
    case "$1" in
        --install-dir)
            INSTALL_DIR="$2"
            shift 2
            ;;
        --skip-jdk)
            SKIP_JDK=true
            shift
            ;;
        --skip-sdk)
            SKIP_SDK=true
            shift
            ;;
        --help|-h)
            grep '^#' "$0" | head -30
            exit 0
            ;;
        *)
            echo "ERROR: Unknown option: $1"
            echo "Run with --help for usage."
            exit 1
            ;;
    esac
done

# Repo root = directory containing this script's parent
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Derived paths
JDK_HOME="$INSTALL_DIR/tools/$JDK_DIR_NAME"
ANDROID_HOME="$INSTALL_DIR/android-sdk"
CMDLINE_TOOLS_DIR="$ANDROID_HOME/cmdline-tools/latest"

# ============================================================================
# Helper functions
# ============================================================================

log() {
    echo -e "\033[1;32m[setup]\033[0m $*"
}

warn() {
    echo -e "\033[1;33m[warn]\033[0m $*" >&2
}

error() {
    echo -e "\033[1;31m[error]\033[0m $*" >&2
    exit 1
}

check_command() {
    if ! command -v "$1" &>/dev/null; then
        error "Required command not found: $1\nPlease install it first (e.g. apt-get install $2)"
    fi
}

# ============================================================================
# Pre-flight checks
# ============================================================================

log "Protect-Yourself development environment setup"
log "Install directory: $INSTALL_DIR"
log "Repo root: $REPO_ROOT"
echo ""

check_command curl "curl"
check_command unzip "unzip"
check_command tar "tar"

# Create install directories
mkdir -p "$INSTALL_DIR/tools"
mkdir -p "$ANDROID_HOME"

# ============================================================================
# Step 1: Install JDK 17
# ============================================================================

if [[ "$SKIP_JDK" == "true" ]]; then
    log "Skipping JDK download (--skip-jdk)"
    if [[ -z "${JAVA_HOME:-}" ]]; then
        warn "JAVA_HOME is not set. Builds may fail if the system Java is not JDK 17+."
    else
        log "Using existing JAVA_HOME=$JAVA_HOME"
    fi
else
    if [[ -x "$JDK_HOME/bin/java" ]]; then
        log "JDK 17 already installed at: $JDK_HOME"
        log "  Version: $($JDK_HOME/bin/java -version 2>&1 | head -1)"
    else
        log "Downloading JDK 17 (Temurin 17.0.13+11)..."
        log "  URL: $JDK_DOWNLOAD_URL"
        TMP_JDK_TAR="/tmp/jdk17-setup.tar.gz"
        curl -fSL --retry 3 -o "$TMP_JDK_TAR" "$JDK_DOWNLOAD_URL" || error "JDK download failed"

        log "Extracting JDK to $INSTALL_DIR/tools/ ..."
        tar xzf "$TMP_JDK_TAR" -C "$INSTALL_DIR/tools/"
        rm -f "$TMP_JDK_TAR"

        if [[ ! -x "$JDK_HOME/bin/java" ]]; then
            error "JDK extraction failed — $JDK_HOME/bin/java not found after extract"
        fi
        log "JDK 17 installed: $($JDK_HOME/bin/java -version 2>&1 | head -1)"
    fi
    export JAVA_HOME="$JDK_HOME"
fi

export PATH="$JAVA_HOME/bin:$PATH"

echo ""

# ============================================================================
# Step 2: Install Android cmdline-tools
# ============================================================================

if [[ "$SKIP_SDK" == "true" ]]; then
    log "Skipping SDK download (--skip-sdk)"
    if [[ -z "${ANDROID_HOME:-}" ]]; then
        warn "ANDROID_HOME is not set. Builds may fail."
    else
        log "Using existing ANDROID_HOME=$ANDROID_HOME"
    fi
else
    if [[ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]]; then
        log "Android cmdline-tools already installed at: $CMDLINE_TOOLS_DIR"
    else
        log "Downloading Android cmdline-tools..."
        log "  URL: $CMDLINE_TOOLS_URL"
        TMP_SDK_ZIP="/tmp/cmdline-tools-setup.zip"
        curl -fSL --retry 3 -o "$TMP_SDK_ZIP" "$CMDLINE_TOOLS_URL" || error "cmdline-tools download failed"

        log "Extracting cmdline-tools to $ANDROID_HOME/cmdline-tools/ ..."
        mkdir -p "$ANDROID_HOME/cmdline-tools"
        unzip -q -o "$TMP_SDK_ZIP" -d "$ANDROID_HOME/cmdline-tools/"
        rm -f "$TMP_SDK_ZIP"

        # Google ships as cmdline-tools/; we need cmdline-tools/latest/
        if [[ -d "$ANDROID_HOME/cmdline-tools/cmdline-tools" ]]; then
            mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$CMDLINE_TOOLS_DIR"
        fi

        if [[ ! -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]]; then
            error "cmdline-tools extraction failed — sdkmanager not found at $CMDLINE_TOOLS_DIR/bin/"
        fi
        log "Android cmdline-tools installed."
    fi
    export ANDROID_HOME="$ANDROID_HOME"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo ""

# ============================================================================
# Step 3: Accept SDK licenses + install packages
# ============================================================================

if [[ "$SKIP_SDK" == "true" ]]; then
    log "Skipping SDK package installation (--skip-sdk)"
else
    log "Accepting Android SDK licenses..."
    yes | sdkmanager --licenses > /dev/null 2>&1 || warn "Some licenses may have failed — check output above"

    log "Installing SDK packages:"
    for pkg in "${SDK_PACKAGES[@]}"; do
        log "  • $pkg"
    done
    sdkmanager "${SDK_PACKAGES[@]}" 2>&1 | tail -5 || error "SDK package installation failed"
    log "SDK packages installed."
fi

echo ""

# ============================================================================
# Step 4: Create local.properties
# ============================================================================

LOCAL_PROPERTIES="$REPO_ROOT/local.properties"
log "Writing local.properties..."
cat > "$LOCAL_PROPERTIES" << EOF
# Auto-generated by setup/setup-env.sh
# Points Gradle to the Android SDK location.
# DO NOT COMMIT THIS FILE — it's in .gitignore and machine-specific.
sdk.dir=$ANDROID_HOME
EOF
log "  Written to: $LOCAL_PROPERTIES"
log "  sdk.dir=$ANDROID_HOME"

echo ""

# ============================================================================
# Step 5: Verify setup
# ============================================================================

log "Verifying setup..."

log "  JAVA_HOME=$JAVA_HOME"
log "  Java version: $(java -version 2>&1 | head -1)"
log "  ANDROID_HOME=$ANDROID_HOME"
log "  Platforms: $(ls "$ANDROID_HOME/platforms/" 2>/dev/null | tr '\n' ' ')"
log "  Build-tools: $(ls "$ANDROID_HOME/build-tools/" 2>/dev/null | tr '\n' ' ')"

echo ""

log "Running 'gradlew help' to verify the build environment..."
cd "$REPO_ROOT"
if ./gradlew --no-daemon help > /dev/null 2>&1; then
    log "✓ Build environment is ready!"
else
    warn "gradlew help failed — check the output above."
    warn "This may be normal on the first run (Gradle needs to download distributions)."
    warn "Try running: ./gradlew --no-daemon help"
fi

echo ""

# ============================================================================
# Done — print next steps
# ============================================================================

cat << 'EOF'

╔══════════════════════════════════════════════════════════════════╗
║  Environment setup complete!                                     ║
╠══════════════════════════════════════════════════════════════════╣
║                                                                  ║
║  Next steps:                                                     ║
║                                                                  ║
║    # Build debug APK                                             ║
║    ./gradlew --no-daemon :app:assembleDebug                      ║
║                                                                  ║
║    # Build release APK                                           ║
║    ./gradlew --no-daemon :app:assembleRelease                    ║
║                                                                  ║
║    # Run unit tests                                              ║
║    ./gradlew --no-daemon :app:testDebugUnitTest                  ║
║                                                                  ║
║  Environment variables (set for this session):                   ║
║    JAVA_HOME=<auto-detected>                                     ║
║    ANDROID_HOME=<auto-detected>                                  ║
║                                                                  ║
║  To set them permanently, add to your ~/.bashrc:                 ║
║    export JAVA_HOME=<JDK path>                                   ║
║    export ANDROID_HOME=<SDK path>                                ║
║    export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/      ║
║    latest/bin:$ANDROID_HOME/platform-tools:$PATH"                ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝

EOF

log "Setup completed in $SECONDS seconds."
