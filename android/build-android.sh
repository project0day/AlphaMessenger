#!/bin/bash
# Build the AlphaMessenger Rust core for Android targets.
#
# Prerequisites:
#   1. Android NDK installed (ANDROID_NDK_HOME set)
#   2. cargo-ndk installed: cargo install cargo-ndk
#   3. Rust Android targets: rustup target add aarch64-linux-android x86_64-linux-android
#
# Key configuration:
#   - reqwest uses rustls-tls (not native-tls/openssl) for Android compatibility
#   - rusqlite uses bundled-sqlcipher-vendored-openssl (bundles both SQLCipher and OpenSSL)
#   - OPENSSL_NO_ASM=1 is needed for x86_64 emulator builds (NDK doesn't support
#     x86_64 assembly in OpenSSL's sm4 module)
#
# Usage:
#   ./build-android.sh          # Build all targets
#   ./build-android.sh arm64    # Build arm64-v8a only (for real devices)
#   ./build-android.sh x86_64   # Build x86_64 only (for emulator)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUST_DIR="$SCRIPT_DIR/../rust-core"
ANDROID_DIR="$SCRIPT_DIR"

# Android NDK detection
if [ -z "$ANDROID_NDK_HOME" ]; then
    # Try common locations
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d $HOME/Android/Sdk/ndk/*/ | tail -1)
    elif [ -d "$ANDROID_HOME/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d $ANDROID_HOME/ndk/*/ | tail -1)
    elif [ -d "$HOME/android-ndk-r26d" ]; then
        ANDROID_NDK_HOME="$HOME/android-ndk-r26d"
    elif [ -d "/opt/android-ndk" ]; then
        ANDROID_NDK_HOME="/opt/android-ndk"
    fi
fi

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set and NDK not found in common locations."
    echo "Please install the Android NDK and set ANDROID_NDK_HOME."
    exit 1
fi

echo "Using Android NDK: $ANDROID_NDK_HOME"

# Determine which targets to build
TARGETS=()
case "${1:-all}" in
    arm64)
        TARGETS=("aarch64-linux-android")
        ;;
    x86_64)
        TARGETS=("x86_64-linux-android")
        ;;
    all)
        TARGETS=("aarch64-linux-android" "x86_64-linux-android")
        ;;
    *)
        echo "Unknown target: $1"
        echo "Usage: $0 [arm64|x86_64|all]"
        exit 1
        ;;
esac

# Map Rust targets to Android ABI directories
target_to_abi() {
    case "$1" in
        aarch64-linux-android) echo "arm64-v8a" ;;
        x86_64-linux-android) echo "x86_64" ;;
    esac
}

echo "Building AlphaMessenger for Android..."
echo "Targets: ${TARGETS[*]}"
echo ""

cd "$RUST_DIR"

for TARGET in "${TARGETS[@]}"; do
    ABI=$(target_to_abi "$TARGET")
    echo "── Building for $TARGET (Android $ABI) ──"

    # For x86_64, set OPENSSL_NO_ASM=1 because the Android NDK's clang
    # doesn't support x86_64 assembly instructions like vsm4rnds4
    # that OpenSSL 3.x tries to use.
    if [ "$TARGET" = "x86_64-linux-android" ]; then
        export OPENSSL_NO_ASM=1
        echo "  (Using OPENSSL_NO_ASM=1 for x86_64)"
    fi

    # Build with cargo-ndk
    cargo ndk -t "$TARGET" -o "$ANDROID_DIR/app/src/main/jniLibs" build --release -p alpha-ffi

    # cargo-ndk automatically copies the .so to the correct jniLibs subdirectory
    echo "  ✅ Built for $ABI"
    echo ""

    # Reset env for next target
    unset OPENSSL_NO_ASM
done

echo "═══════════════════════════════════════════════════"
echo "  Android .so files built successfully!"
echo "  Output: android/app/src/main/jniLibs/"
echo ""
echo "  arm64-v8a:  Real Android devices (Pixel, Samsung, etc.)"
echo "  x86_64:     Android emulator"
echo "═══════════════════════════════════════════════════"
