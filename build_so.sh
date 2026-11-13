#!/bin/bash
#
# Builds the core's native library for Android and checks it against the
# generated Kotlin bindings.
#
# UniFFI checks every method's checksum against the library at load time. Bindings
# regenerated without rebuilding the library still compile and install, then the
# app dies at start with UnsatisfiedLinkError. This catches it before install.
#
# The NDK must have a toolchain for the machine building: the Windows NDK has none
# for Linux, so it cannot be used from WSL.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNI_DIR="$ROOT/android/app/src/main/jniLibs"
BINDINGS="$ROOT/android/app/src/main/java/uniffi/alphamessenger/alphamessenger.kt"
TARGET="${TARGET:-aarch64-linux-android}"
ABI_DIR="arm64-v8a"

# ── 1. Find the NDK ─────────────────────────────────────────────────────────
host_tag() {
    case "$(uname -s)" in
        Linux*)  echo "linux-x86_64" ;;
        Darwin*) echo "darwin-x86_64" ;;
        *)       echo "windows-x86_64" ;;
    esac
}
HOST="$(host_tag)"

usable_ndk() {
    [ -n "${1:-}" ] && [ -x "$1/toolchains/llvm/prebuilt/$HOST/bin/clang" ]
}

NDK=""
for candidate in \
    "${ANDROID_NDK_HOME:-}" \
    "${ANDROID_NDK_ROOT:-}" \
    "$HOME/Android/Sdk/ndk"/* \
    "${ANDROID_HOME:-/nonexistent}/ndk"/* ; do
    if usable_ndk "$candidate"; then NDK="$candidate"; break; fi
done

if [ -z "$NDK" ]; then
    echo "ERROR: no Android NDK with a $HOST toolchain found." >&2
    echo "Set it explicitly:  export ANDROID_NDK_HOME=/path/to/android-ndk" >&2
    exit 1
fi
export ANDROID_NDK_HOME="$NDK"
echo "NDK: $NDK ($HOST)"

# ── 2. Build ────────────────────────────────────────────────────────────────
cd "$ROOT/rust-core"
cargo ndk -t "$TARGET" -o "$JNI_DIR" build --release -p alpha-ffi

SO="$JNI_DIR/$ABI_DIR/libalpha_ffi.so"
[ -f "$SO" ] || { echo "ERROR: library not built: $SO" >&2; exit 1; }

# ── 3. Check the bindings against the library ───────────────────────────────
if [ -f "$BINDINGS" ]; then
    echo "Checking bindings against the library…"
    SYMTAB="$(mktemp)"
    trap 'rm -f "$SYMTAB"' EXIT
    # One symbol dump for all checks: the library is large.
    nm -D --defined-only "$SO" 2>/dev/null > "$SYMTAB" || strings "$SO" > "$SYMTAB"

    MISSING=0
    while read -r symbol; do
        [ -z "$symbol" ] && continue
        if ! grep -qw "$symbol" "$SYMTAB"; then
            echo "  missing from the library: $symbol" >&2
            MISSING=$((MISSING + 1))
        fi
    done < <(grep -o 'uniffi_alpha_ffi_checksum_[a-z0-9_]*' "$BINDINGS" | sort -u)

    if [ "$MISSING" -gt 0 ]; then
        echo "ERROR: the bindings need $MISSING symbol(s) libalpha_ffi.so does not have." >&2
        echo "The UDL changed and the bindings were regenerated, but the library was not rebuilt." >&2
        exit 1
    fi
    echo "  bindings match"
else
    echo "WARNING: bindings not found, check skipped." >&2
fi

echo "Done: $SO ($(stat -c %s "$SO" 2>/dev/null || echo '?') bytes)"
