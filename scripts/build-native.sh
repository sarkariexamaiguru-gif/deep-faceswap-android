#!/usr/bin/env bash
#
# Cross-compile the Rust core for Android (arm64-v8a) and drop the resulting
# shared library into the Android app's jniLibs directory.
#
# Requirements:
#   - Rust toolchain with the aarch64-linux-android target installed
#   - cargo-ndk            (cargo install cargo-ndk)
#   - Android NDK          ($ANDROID_NDK_HOME, or auto-detected by cargo-ndk)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/jniLibs"

echo "Building libdeep_faceswap_android.so (arm64-v8a) ..."
cd "$ROOT/rust"
cargo ndk -t arm64-v8a -o "$OUT" build --release -p deep_faceswap_android

echo
echo "Done. Native library written to:"
ls -R "$OUT"
