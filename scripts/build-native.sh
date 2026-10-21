#!/usr/bin/env bash
#
# Cross-compile the Rust core for Android (arm64-v8a) and stage everything the
# app needs into jniLibs:
#   - libdeep_faceswap_android.so  (our JNI bridge, ONNX Runtime linked in)
#   - libc++_shared.so             (NDK C++ runtime — REQUIRED at load time)
#
# Requirements:
#   - Rust toolchain with the aarch64-linux-android target installed
#   - cargo-ndk            (cargo install cargo-ndk)
#   - Android NDK          ($ANDROID_NDK_HOME / $ANDROID_NDK_ROOT, or under
#                           $ANDROID_SDK_ROOT/ndk/*)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/jniLibs"
ABI="arm64-v8a"
TRIPLE="aarch64-linux-android"

# Locate the NDK. cargo-ndk can find it on its own, but we need it here too so
# we can copy libc++_shared.so out of it.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    NDK="$(ls -d "$ANDROID_SDK_ROOT"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi

echo "Building libdeep_faceswap_android.so ($ABI) ..."
cd "$ROOT/rust"
cargo ndk -t "$ABI" -o "$OUT" build --release -p deep_faceswap_android

# Our cdylib links against libc++_shared.so (pulled in by ONNX Runtime's static
# libraries). Android does not bundle it automatically, and without it
# System.loadLibrary() fails with "library libc++_shared.so not found".
# Note: the sysroot is keyed by the target triple, not the ABI name.
STL=""
if [ -n "$NDK" ] && [ -d "$NDK" ]; then
    for tag in linux-x86_64 darwin-x86_64; do
        candidate="$NDK/toolchains/llvm/prebuilt/$tag/sysroot/usr/lib/$TRIPLE/libc++_shared.so"
        if [ -f "$candidate" ]; then
            STL="$candidate"
            break
        fi
    done
    # Fallback: search the NDK for any matching copy.
    if [ -z "$STL" ]; then
        STL="$(find "$NDK" -type f -name 'libc++_shared.so' -path "*$TRIPLE*" 2>/dev/null | head -n1 || true)"
    fi
    if [ -z "$STL" ]; then
        STL="$(find "$NDK" -type f -name 'libc++_shared.so' 2>/dev/null | head -n1 || true)"
    fi
fi

if [ -n "$STL" ]; then
    mkdir -p "$OUT/$ABI"
    cp "$STL" "$OUT/$ABI/"
    echo "Copied libc++_shared.so from $STL"
else
    echo "ERROR: could not locate libc++_shared.so in the NDK ($NDK)." >&2
    echo "       Without it the app crashes on launch." >&2
    if [ -n "$NDK" ] && [ -d "$NDK" ]; then
        echo "       Candidates found:" >&2
        find "$NDK" -name 'libc++*' 2>/dev/null | head -n 20 >&2 || true
    fi
    exit 1
fi

echo
echo "Done. jniLibs contents:"
ls -R "$OUT"
