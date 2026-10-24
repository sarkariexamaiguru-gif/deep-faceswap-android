//! HRFFA: landmarks, and whether each one is actually visible.
//!
//! XSeg reads the pixels and says where skin is. This reads the geometry and
//! says which points are behind something. Between them the occlusion mask stops
//! guessing - and where an object is skin-coloured, a hand or a forearm, XSeg can
//! be fooled and this cannot.
//!
//! Contract taken from the model's own ONNX documentation rather than inferred:
//!
//! ```text
//! input   images      float32 [N, 3, S, S]     S = 320 / 256 / 96
//! output  points      float32 [N, K, 2]        coordinates 0..1 within the crop
//! output  vis_logits  float32 [N, K, 3]        0 outside, 1 occluded, 2 visible
//! ```
//!
//! Normalisation for the student models is `(x / 255 - 0.5) / 0.5`, NOT the
//! ImageNet mean and standard deviation - the teacher uses ImageNet and the
//! students do not, which is exactly the kind of detail that produces a
//! plausible-looking wrong answer.
//!
//! One honest limitation: the model wants a whole-HEAD crop, and this app has a
//! face detector, not a head detector. The face box is expanded by
//! [`HEAD_EXPANSION`] to approximate one. That is an approximation, and it is
//! the reason the visibility signal is used to TIGHTEN the mask rather than to
//! drive it.

use crate::config;
use crate::types::{FaceSwapError, Result};
use ndarray::{Array3, Array4};
use ort::{
    inputs,
    session::{builder::GraphOptimizationLevel, Session},
    value::TensorRef,
};
use std::sync::Mutex;

/// The input size the student exports are built at. 256 is the accuracy tier;
/// 96 is the fast one.
const SIZE: usize = 256;

/// How much to grow the face box to approximate a head box.
const HEAD_EXPANSION: f32 = 1.4;

/// Radius, as a fraction of the crop, to protect around a hidden point. Wide
/// enough to cover the landmark's neighbourhood, narrow enough not to eat the
/// face.
const PROTECT_RADIUS: f32 = 0.035;

// ---------------------------------------------------------------- the slot --

static LANDMARKS: Mutex<Option<Landmarker>> = Mutex::new(None);

struct Landmarker {
    path: String,
    session: Session,
}

/// Point the visibility check at an HRFFA model. An empty path turns it off.
///
/// Same reasoning as the occluder: a process-wide slot rather than threading
/// through the swap signature and its call layers, and a model that will not
/// load turns the feature off rather than failing every frame.
pub fn set_model_path(path: &str) {
    let mut slot = match LANDMARKS.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    if path.is_empty() {
        *slot = None;
        return;
    }
    if slot.as_ref().map(|l| l.path == path).unwrap_or(false) {
        return;
    }
    *slot = match load(path) {
        Ok(session) => Some(Landmarker {
            path: path.to_string(),
            session,
        }),
        Err(_) => None,
    };
}

pub fn is_ready() -> bool {
    let slot = match LANDMARKS.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    slot.is_some()
}

pub fn load(model_path: &str) -> Result<Session> {
    Session::builder()
        .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
        .with_optimization_level(GraphOptimizationLevel::Level3)
        .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
        .with_intra_threads(config::intra_threads())
        .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
        .commit_from_file(model_path)
        .map_err(|e| FaceSwapError::Ort(format!("{}", e)))
}

/// One predicted point, in ORIGINAL IMAGE pixels.
///
/// The model returns 0..1 within the head crop. Converting here, where the crop
/// geometry is known, keeps every caller from having to reconstruct it - and
/// the head crop and a face crop are not the same square, so a caller that
/// assumed they were would be subtly wrong rather than obviously wrong.
pub struct Point {
    pub x: f32,
    pub y: f32,
    /// True when the model says this point is behind something.
    pub occluded: bool,
}

/// Predict landmarks and visibility for a face region.
///
/// Returns `None` when no model is loaded, so the caller can carry on without
/// visibility rather than failing.
pub fn predict(image: &Array3<u8>, bbox: &crate::types::BBox) -> Option<Vec<Point>> {
    let mut slot = match LANDMARKS.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    let landmarker = slot.as_mut()?;

    let (h, w, _) = image.dim();
    let cx = (bbox.x1 + bbox.x2) * 0.5;
    let cy = (bbox.y1 + bbox.y2) * 0.5;
    let side = ((bbox.x2 - bbox.x1).max(bbox.y2 - bbox.y1)) * HEAD_EXPANSION;
    if side < 8.0 {
        return None;
    }
    let half = side * 0.5;

    // The crop is allowed to run off the edge; the model is told about points
    // that fall outside rather than being fed black, which is what its third
    // visibility class exists for.
    let mut input = Array4::<f32>::zeros((1, 3, SIZE, SIZE));
    let step = side / SIZE as f32;
    for y in 0..SIZE {
        let sy = cy - half + (y as f32 + 0.5) * step;
        for x in 0..SIZE {
            let sx = cx - half + (x as f32 + 0.5) * step;
            let (px, py) = (sx.round() as isize, sy.round() as isize);
            for c in 0..3 {
                let value = if px >= 0 && py >= 0 && (px as usize) < w && (py as usize) < h {
                    image[[py as usize, px as usize, c]] as f32
                } else {
                    0.0
                };
                // center05, not ImageNet.
                input[[0, c, y, x]] = value / 127.5 - 1.0;
            }
        }
    }

    let input_name = session_input(landmarker)?;
    let (points_name, vis_name) = session_outputs(landmarker)?;

    let outputs = landmarker
        .session
        .run(inputs![input_name.as_str() => TensorRef::from_array_view(input.view()).ok()?])
        .ok()?;

    let points = outputs[points_name.as_str()].try_extract_array::<f32>().ok()?;
    let vis = outputs[vis_name.as_str()].try_extract_array::<f32>().ok()?;

    let pshape = points.shape().to_vec();
    let vshape = vis.shape().to_vec();
    if pshape.len() != 3 || vshape.len() != 3 {
        return None;
    }
    let count = pshape[1].min(vshape[1]);

    let mut out = Vec::with_capacity(count);
    for k in 0..count {
        let x = points[[0, k, 0]];
        let y = points[[0, k, 1]];
        // Three classes: 0 outside the image, 1 occluded, 2 visible.
        let mut best = 0usize;
        let mut best_value = f32::MIN;
        for c in 0..3.min(vshape[2]) {
            let v = vis[[0, k, c]];
            if v > best_value {
                best_value = v;
                best = c;
            }
        }
        out.push(Point {
            x: cx - half + x * side,
            y: cy - half + y * side,
            occluded: best == 1,
        });
    }
    Some(out)
}

fn session_input(landmarker: &Landmarker) -> Option<String> {
    landmarker
        .session
        .inputs()
        .iter()
        .find(|i| i.name() == "images")
        .or_else(|| landmarker.session.inputs().first())
        .map(|i| i.name().to_string())
}

fn session_outputs(landmarker: &Landmarker) -> Option<(String, String)> {
    let mut points = None;
    let mut vis = None;
    for output in landmarker.session.outputs() {
        let name = output.name();
        if name.contains("vis") {
            vis = Some(name.to_string());
        } else if name.contains("point") {
            points = Some(name.to_string());
        }
    }
    let points = points.or_else(|| landmarker.session.outputs().first().map(|o| o.name().to_string()))?;
    let vis = vis.or_else(|| landmarker.session.outputs().get(1).map(|o| o.name().to_string()))?;
    Some((points, vis))
}

/// The protect radius in pixels for a crop.
pub fn protect_radius(crop_w: usize, crop_h: usize) -> isize {
    ((crop_w.min(crop_h) as f32) * PROTECT_RADIUS).round().max(2.0) as isize
}
