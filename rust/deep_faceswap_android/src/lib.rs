//! JNI bridge between the Android app (`com.deepfaceswap.android.NativeBridge`)
//! and the Rust `deep_faceswap_core` engine.
//!
//! Every entry point returns a JSON string so the Kotlin side has a single,
//! stable way to read results and errors:
//!
//! ```json
//! { "ok": true }
//! { "ok": false, "error": "No faces detected in image" }
//! ```

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jfloat, jint, jstring};
use jni::JNIEnv;
use std::sync::atomic::{AtomicI32, Ordering};

use deep_faceswap_core::swap::{SwapOptions, SwapStage, MAX_PIXEL_BOOST};

/// Latest progress stage, polled from Kotlin while the swap runs on a worker
/// thread. `-1` means "no swap in flight".
static STAGE: AtomicI32 = AtomicI32::new(-1);
/// Faces finished in the current swap.
static FACES_DONE: AtomicI32 = AtomicI32::new(0);
/// Faces the current swap will process.
static FACES_TOTAL: AtomicI32 = AtomicI32::new(0);

/// Copy a Java string into a Rust `String` (empty string on failure).
fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.into())
        .unwrap_or_default()
}

/// Allocate a new Java string from a Rust `String`.
fn to_jstring(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Run a face swap and return `{"ok":true}` or `{"ok":false,"error":"..."}`.
///
/// * `source_paths` – comma-separated paths to source face images
/// * `target_path` – path to the target image
/// * `output_path` – path to write the result to
/// * `detector` / `recognizer` / `swapper` – ONNX model paths
/// * `enhancer` / `landmark` – optional model paths (pass an empty string to disable)
/// * `use_mouth_mask` / `use_multi_face` – feature toggles
/// * `pixel_boost` – 1..=8, i.e. 128 / 256 / 384 / 512 / 768 / 1024px swap resolution
/// * `enhancer_strength` – 0.0..=1.0 blend of the restored face
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_swapImage(
    mut env: JNIEnv,
    _class: JClass,
    source_paths: JString,
    target_path: JString,
    output_path: JString,
    detector: JString,
    recognizer: JString,
    swapper: JString,
    enhancer: JString,
    landmark: JString,
    use_mouth_mask: jboolean,
    use_multi_face: jboolean,
    pixel_boost: jint,
    enhancer_strength: jfloat,
) -> jstring {
    let source = jstring_to_string(&mut env, &source_paths);
    let target = jstring_to_string(&mut env, &target_path);
    let output = jstring_to_string(&mut env, &output_path);
    let detector = jstring_to_string(&mut env, &detector);
    let recognizer = jstring_to_string(&mut env, &recognizer);
    let swapper = jstring_to_string(&mut env, &swapper);
    let enhancer = jstring_to_string(&mut env, &enhancer);
    let landmark = jstring_to_string(&mut env, &landmark);

    let enhancer_opt = if enhancer.is_empty() {
        None
    } else {
        Some(enhancer.as_str())
    };
    let landmark_opt = if landmark.is_empty() {
        None
    } else {
        Some(landmark.as_str())
    };

    let mouth_mask = use_mouth_mask != 0;
    let multi_face = use_multi_face != 0;

    // The app's pipeline settings: how hard to work on resolution and how much
    // of the restored face to keep.
    let opts = SwapOptions {
        use_mouth_mask: mouth_mask,
        use_multi_face: multi_face,
        pixel_boost: pixel_boost.clamp(1, MAX_PIXEL_BOOST as i32) as u32,
        enhancer_strength: enhancer_strength.clamp(0.0, 1.0),
    };

    // Reset progress so a stale value from a previous run can never be shown.
    STAGE.store(SwapStage::LoadingModels as i32, Ordering::SeqCst);
    FACES_DONE.store(0, Ordering::SeqCst);
    FACES_TOTAL.store(0, Ordering::SeqCst);

    let mut on_stage = |stage: SwapStage, done: usize, total: usize| {
        STAGE.store(stage as i32, Ordering::SeqCst);
        FACES_DONE.store(done as i32, Ordering::SeqCst);
        FACES_TOTAL.store(total as i32, Ordering::SeqCst);
    };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        deep_faceswap_core::swap::swap_faces_reporting(
            &source,
            &target,
            &output,
            &detector,
            &recognizer,
            &swapper,
            enhancer_opt,
            landmark_opt,
            opts,
            &mut on_stage,
        )
    }));

    let payload = match result {
        Ok(Ok(())) => serde_json::json!({ "ok": true }).to_string(),
        Ok(Err(e)) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        }
        Err(_) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": "native panic during face swap" })
                .to_string()
        }
    };

    to_jstring(&mut env, payload)
}

/// Returns the crate version so the app can display / log it.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_coreVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&mut env, env!("CARGO_PKG_VERSION").to_string())
}

/// Current progress stage, or `-1` when no swap is running.
///
/// Values match `deep_faceswap_core::swap::SwapStage`:
/// `0` models, `1` source, `2` detect, `3` swapping, `4` saving, `5` done.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_progress(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    STAGE.load(Ordering::SeqCst)
}

/// Face progress packed as `(done << 16) | total` for the current swap.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_faceProgress(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let done = FACES_DONE.load(Ordering::SeqCst).clamp(0, 0xFFFF);
    let total = FACES_TOTAL.load(Ordering::SeqCst).clamp(0, 0xFFFF);
    (done << 16) | total
}

/// Detect faces for the manual mapping UI.
///
/// Crops are written under `crops_dir` and their paths returned, so the app can
/// show the faces and let the user pair them up.
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_nativeDetectFaces(
    mut env: JNIEnv,
    _class: JClass,
    source_paths: JString,
    target_path: JString,
    detector: JString,
    crops_dir: JString,
) -> jstring {
    let source = jstring_to_string(&mut env, &source_paths);
    let target = jstring_to_string(&mut env, &target_path);
    let detector = jstring_to_string(&mut env, &detector);
    let crops_dir = jstring_to_string(&mut env, &crops_dir);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        deep_faceswap_core::swap::detect_faces_for_mapping(&source, &target, &detector, &crops_dir)
    }));

    let payload = match result {
        Ok(Ok(faces)) => serde_json::json!({
            "ok": true,
            "source": faces.source.iter().map(face_to_json).collect::<Vec<_>>(),
            "target": faces.target.iter().map(face_to_json).collect::<Vec<_>>(),
        })
        .to_string(),
        Ok(Err(e)) => serde_json::json!({ "ok": false, "error": e.to_string() }).to_string(),
        Err(_) => serde_json::json!({
            "ok": false,
            "error": "native panic during face detection"
        })
        .to_string(),
    };

    to_jstring(&mut env, payload)
}

fn face_to_json(face: &deep_faceswap_core::swap::MappingFace) -> serde_json::Value {
    serde_json::json!({
        "index": face.index,
        "score": face.score,
        "bbox": face.bbox,
        "crop": face.crop_path,
    })
}

/// Swap with explicit source->target pairs, e.g. `"0:0,0:1"`.
///
/// Indices are positions in detection order (highest score first), matching what
/// [`Java_com_deepfaceswap_android_NativeBridge_detectFaces`] returned.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_swapImageMapped(
    mut env: JNIEnv,
    _class: JClass,
    source_paths: JString,
    target_path: JString,
    output_path: JString,
    detector: JString,
    recognizer: JString,
    swapper: JString,
    enhancer: JString,
    landmark: JString,
    mappings: JString,
    use_mouth_mask: jboolean,
    pixel_boost: jint,
    enhancer_strength: jfloat,
) -> jstring {
    let source = jstring_to_string(&mut env, &source_paths);
    let target = jstring_to_string(&mut env, &target_path);
    let output = jstring_to_string(&mut env, &output_path);
    let detector = jstring_to_string(&mut env, &detector);
    let recognizer = jstring_to_string(&mut env, &recognizer);
    let swapper = jstring_to_string(&mut env, &swapper);
    let enhancer = jstring_to_string(&mut env, &enhancer);
    let landmark = jstring_to_string(&mut env, &landmark);
    let mapping_spec = jstring_to_string(&mut env, &mappings);

    let pairs: Vec<(usize, usize)> = mapping_spec
        .split(',')
        .filter(|pair| !pair.trim().is_empty())
        .filter_map(|pair| {
            let mut parts = pair.split(':');
            let source_idx = parts.next()?.trim().parse::<usize>().ok()?;
            let target_idx = parts.next()?.trim().parse::<usize>().ok()?;
            Some((source_idx, target_idx))
        })
        .collect();

    let enhancer_opt = if enhancer.is_empty() { None } else { Some(enhancer.as_str()) };
    let landmark_opt = if landmark.is_empty() { None } else { Some(landmark.as_str()) };

    let opts = SwapOptions {
        use_mouth_mask: use_mouth_mask != 0,
        use_multi_face: true,
        pixel_boost: pixel_boost.clamp(1, MAX_PIXEL_BOOST as i32) as u32,
        enhancer_strength: enhancer_strength.clamp(0.0, 1.0),
    };

    STAGE.store(SwapStage::LoadingModels as i32, Ordering::SeqCst);
    FACES_DONE.store(0, Ordering::SeqCst);
    FACES_TOTAL.store(pairs.len() as i32, Ordering::SeqCst);

    let mut on_stage = |stage: SwapStage, done: usize, total: usize| {
        STAGE.store(stage as i32, Ordering::SeqCst);
        FACES_DONE.store(done as i32, Ordering::SeqCst);
        FACES_TOTAL.store(total as i32, Ordering::SeqCst);
    };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        deep_faceswap_core::swap::swap_faces_mapped(
            &source,
            &target,
            &output,
            &detector,
            &recognizer,
            &swapper,
            enhancer_opt,
            landmark_opt,
            opts,
            &pairs,
            &mut on_stage,
        )
    }));

    let payload = match result {
        Ok(Ok(())) => serde_json::json!({ "ok": true }).to_string(),
        Ok(Err(e)) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        }
        Err(_) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": "native panic during face swap" })
                .to_string()
        }
    };

    to_jstring(&mut env, payload)
}

/// Swap faces across every frame of an animated GIF or WebP.
///
/// `mappings` is the same `"s:t,s:t"` spec as `swapImageMapped`; pass an empty
/// string to map automatically. The result is always written as a GIF.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_deepfaceswap_android_NativeBridge_swapAnimation(
    mut env: JNIEnv,
    _class: JClass,
    source_paths: JString,
    target_path: JString,
    output_path: JString,
    detector: JString,
    recognizer: JString,
    swapper: JString,
    enhancer: JString,
    landmark: JString,
    mappings: JString,
    use_mouth_mask: jboolean,
    pixel_boost: jint,
    enhancer_strength: jfloat,
) -> jstring {
    let source = jstring_to_string(&mut env, &source_paths);
    let target = jstring_to_string(&mut env, &target_path);
    let output = jstring_to_string(&mut env, &output_path);
    let detector = jstring_to_string(&mut env, &detector);
    let recognizer = jstring_to_string(&mut env, &recognizer);
    let swapper = jstring_to_string(&mut env, &swapper);
    let enhancer = jstring_to_string(&mut env, &enhancer);
    let landmark = jstring_to_string(&mut env, &landmark);
    let mapping_spec = jstring_to_string(&mut env, &mappings);

    let pairs: Vec<(usize, usize)> = mapping_spec
        .split(',')
        .filter(|pair| !pair.trim().is_empty())
        .filter_map(|pair| {
            let mut parts = pair.split(':');
            let source_idx = parts.next()?.trim().parse::<usize>().ok()?;
            let target_idx = parts.next()?.trim().parse::<usize>().ok()?;
            Some((source_idx, target_idx))
        })
        .collect();

    let enhancer_opt = if enhancer.is_empty() { None } else { Some(enhancer.as_str()) };
    let landmark_opt = if landmark.is_empty() { None } else { Some(landmark.as_str()) };

    let opts = SwapOptions {
        use_mouth_mask: use_mouth_mask != 0,
        use_multi_face: pairs.is_empty(),
        pixel_boost: pixel_boost.clamp(1, MAX_PIXEL_BOOST as i32) as u32,
        enhancer_strength: enhancer_strength.clamp(0.0, 1.0),
    };

    STAGE.store(SwapStage::LoadingModels as i32, Ordering::SeqCst);
    FACES_DONE.store(0, Ordering::SeqCst);
    FACES_TOTAL.store(0, Ordering::SeqCst);

    let mut on_stage = |stage: SwapStage, done: usize, total: usize| {
        STAGE.store(stage as i32, Ordering::SeqCst);
        FACES_DONE.store(done as i32, Ordering::SeqCst);
        FACES_TOTAL.store(total as i32, Ordering::SeqCst);
    };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        deep_faceswap_core::swap::swap_animation(
            &source,
            &target,
            &output,
            &detector,
            &recognizer,
            &swapper,
            enhancer_opt,
            landmark_opt,
            opts,
            &pairs,
            &mut on_stage,
        )
    }));

    let payload = match result {
        Ok(Ok(frames)) => serde_json::json!({ "ok": true, "frames": frames }).to_string(),
        Ok(Err(e)) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        }
        Err(_) => {
            STAGE.store(-1, Ordering::SeqCst);
            serde_json::json!({ "ok": false, "error": "native panic during animation swap" })
                .to_string()
        }
    };

    to_jstring(&mut env, payload)
}
