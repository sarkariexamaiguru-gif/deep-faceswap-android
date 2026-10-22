# Deep FaceSwap — Android

A **native Android port** of the [`LdDl/deep_faceswap`](https://github.com/LdDl/deep_faceswap)
face-swap engine. The Rust core runs on-device (compiled for `arm64-v8a` through
the Android NDK) and is driven from a Kotlin UI over JNI. No server, no network
round-trip — inference happens entirely on the phone.

> ⚠️ **Use responsibly.** Face swapping can be used to deceive. Only swap faces
> you have permission to use, and do not create or distribute content that
> misrepresents real people without their consent. See [Ethics](#ethics).

---

## What works

| Capability | Status |
|---|---|
| Image face swap (1:1) | ✅ |
| Multiple source images / faces | ✅ (engine supports it; on-device UI currently swaps the highest-scoring source face) |
| Mouth mask (preserve target's mouth) | ✅ (needs `2d106det.onnx`) |
| Face enhancement (GFPGAN) | ⛔ not bundled (optional model, add later) |
| Video face swap | ⛔ disabled — requires FFmpeg, see [Limitations](#limitations) |
| GPU (CUDA) acceleration | n/a on mobile (CPU inference) |

**ABI support: `arm64-v8a` only.** The `ort` crate ships a prebuilt ONNX Runtime
for `aarch64-linux-android` and nothing else, so 32-bit ARM and x86 emulators are
not supported out of the box.

---

## Architecture

```
android/                        Kotlin app (Gradle, framework-only, no AndroidX)
  app/src/main/java/com/deepfaceswap/android/
    MainActivity.kt             Single-screen UI (pick images, run swap, preview)
    NativeBridge.kt             JNI wrapper + JSON result parsing
    ModelManager.kt             Locates / downloads the ONNX models

rust/                           Rust workspace (vendored from upstream)
  deep_faceswap_core/           The engine: detection, alignment, swap, paste-back
  deep_faceswap_android/        cdylib exposing the JNI entry points

scripts/
  build-native.sh               cargo-ndk cross-compile → app/src/main/jniLibs
  extract_emap.py               Build the swapper's `_emap.bin` sidecar
  prepare_models.sh             Download models + print adb push commands

.github/workflows/build-apk.yml Builds the APK in CI
```

The Kotlin side calls one JNI function, `swapImage(...)`, which forwards to the
core's `swap_faces(...)` and returns a JSON string (`{"ok":true}` or
`{"ok":false,"error":"..."}`).

### Changes vs. upstream

The core is vendored and lightly modified so it cross-compiles for Android:

- **FFmpeg is now optional.** `ffmpeg-next` is behind a `video` feature
  (enabled by default). The Android build uses `--no-default-features`, which
  drops the `video` module and the video swap entry points, removing the need to
  cross-compile FFmpeg for every ABI.
- **ONNX Runtime TLS backend** is switched to `rustls` on Android (the NDK
  toolchain has no OpenSSL for `native-tls`).

---

## Building

### Prerequisites

- JDK 17
- Android SDK (platform 34, build-tools 34) and **Android NDK** `26.1.10909125`
- Rust (stable) with the `aarch64-linux-android` target
- `cargo-ndk`

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk --version '^3'
```

### One command

```bash
# 1. Build the native library (Rust → jniLibs)
bash scripts/build-native.sh

# 2. Build the APK (skips the Rust step since we just ran it)
cd android
./gradlew assembleDebug -PskipRust=true
```

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

`./gradlew assembleDebug` on its own will also invoke `cargo-ndk` for you
(drop `-PskipRust=true`); pass `-PskipRust=true` when you have already built the
native library.

### In CI

Push to `main` (or run the **Build APK** workflow manually) — GitHub Actions
installs the JDK, SDK, NDK, Rust and `cargo-ndk`, then uploads the signed
`app-release.apk` as a build artifact.

---

## Signing & updates

Builds are signed with a single, stable key so a new APK installs **over** the
previous one (`adb install -r`) instead of requiring an uninstall first. That
only works if every build uses the *same* signing identity — Android rejects an
update signed with a different key.

The key is a PKCS12 keystore and its credentials are **not** in this repository.
They live in GitHub Actions secrets:

| Secret | Meaning |
|---|---|
| `KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias (`deepfaceswap`) |
| `KEY_PASSWORD` | key password |

`app/build.gradle.kts` reads them from the environment (`SIGNING_STORE_FILE`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). When
they are absent — a fork, or a local build — it falls back to the debug key so
the build still succeeds.

To sign locally, decode the keystore and point Gradle at it:

```bash
echo "$KEYSTORE_BASE64" | base64 -d > release.p12
export SIGNING_STORE_FILE=$PWD/release.p12
export SIGNING_STORE_PASSWORD='…' SIGNING_KEY_ALIAS=deepfaceswap SIGNING_KEY_PASSWORD='…'
cd android && ./gradlew assembleRelease
```

> **Keep the keystore and its passwords safe.** If you lose them you cannot
> update an installed app — you would have to uninstall and start over with a
> new identity. Anyone who has the keystore can sign APKs that Android will
> treat as your app.

---

## Models

The engine needs these files:

```
models/
  buffalo_l/
    det_10g.onnx            face detector
    w600k_r50.onnx          ArcFace recogniser
    2d106det.onnx           landmarks (mouth mask)
  inswapper_128.onnx        face swapper
  inswapper_128_emap.bin    emap sidecar (512x512 f32) — required by the swapper
```

The three buffalo_l models and `inswapper_128.onnx` are **not** committed (they
are large). The 1 MB **emap sidecar is committed** at
`android/app/src/main/assets/inswapper_128_emap.bin`, and the app copies it into
place automatically on launch — so you never have to supply it by hand.

Easiest path: install the app, tap **Check / download models**, and the four
large models are fetched over the network while the emap is unpacked from the
APK. You should end up with `Present: 5/5`.

To prepare everything on a desktop instead:

```bash
pip install onnx numpy
bash scripts/prepare_models.sh      # downloads models + writes the emap
```

`prepare_models.sh` prints the `adb push` commands to sideload the models onto a
device. Note that not every inswapper build names the emap tensor `emap` — the
common `inswapper_128_fp16.onnx` calls it `initializer` — so `extract_emap.py`
locates it by name first and otherwise falls back to the unique 512x512 float32
initialiser.

Models are read from `filesDir/models` (i.e.
`/data/data/com.deepfaceswap.android/files/models`).

---

## Using the app

1. **Pick source face image(s)** — the face(s) to copy *from*.
2. **Pick target image** — the photo to swap *into*.
3. Optionally tick **Preserve target mouth**.
4. **Run swap** — the result is written to the app's external files dir
   (`Android/data/com.deepfaceswap.android/files/outputs/`) and previewed.

---

## Limitations

- **Video is not supported** in this port. Upstream does video via FFmpeg
  (`ffmpeg-next`), and cross-compiling FFmpeg for Android is out of scope here.
  To add it, enable the core's `video` feature and provide FFmpeg `.so`s for
  `arm64-v8a`, then re-expose the video swap functions over JNI.
- **arm64-v8a only** (see above).
- **On-device face mapping for multi-face is not wired up.** The engine exposes
  `swap_with_mappings`, but the Kotlin UI does not yet present the interactive
  crop/mapping flow. Single source → target works today.
- **CPU inference is slow** (seconds per image depending on device).
- GFPGAN enhancement is not bundled.

---

## Ethics

This is a port of a public research/engineering project. Face swapping can
facilitate impersonation, non-consensual intimate imagery, and political
disinformation. If you build on this, add safeguards: consent checks, visible
provenance/watermarking, and a clear "synthetic media" label. Do not use it to
harass, defraud, or misrepresent anyone. Laws in many jurisdictions (including
India's IT Rules and various deepfake statutes) restrict synthetic media of real
people.

---

## License

MIT. The core engine is a port of
[`LdDl/deep_faceswap`](https://github.com/LdDl/deep_faceswap) (MIT), itself a port
of [Deep-Live-Cam](https://github.com/hacksider/Deep-Live-Cam). See `LICENSE`.
