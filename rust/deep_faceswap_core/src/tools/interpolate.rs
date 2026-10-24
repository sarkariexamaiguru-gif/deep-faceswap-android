//! RIFE: the frame that was never filmed.
//!
//! Real-Time Intermediate Flow Estimation. Given two frames and a moment
//! between them, it synthesises the frame that would have been there. For a GIF
//! this is what separates fluid motion from a flipbook.
//!
//! The contract is DISCOVERED rather than assumed, because the published RIFE
//! exports are not consistent about it: some take two images and an implied
//! midpoint, some take `img0` / `img1` / `timestep`. The runner looks for those
//! names and falls back to the first two inputs, and only passes a timestep if a
//! third input actually exists. Guessing wrong here produces a plausible frame
//! at the wrong moment, which is harder to notice than an error.

use crate::config;
use crate::types::{FaceSwapError, Result};
use ndarray::{Array3, Array4};
use ort::{
    inputs,
    session::{builder::GraphOptimizationLevel, Session},
    value::TensorRef,
};
use std::sync::Mutex;

static INTERPOLATOR: Mutex<Option<Interpolator>> = Mutex::new(None);

struct Interpolator {
    path: String,
    session: Session,
}

/// Point interpolation at a RIFE model. An empty path turns it off.
pub fn set_model_path(path: &str) {
    let mut slot = match INTERPOLATOR.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    if path.is_empty() {
        *slot = None;
        return;
    }
    if slot.as_ref().map(|i| i.path == path).unwrap_or(false) {
        return;
    }
    *slot = match load(path) {
        Ok(session) => Some(Interpolator {
            path: path.to_string(),
            session,
        }),
        Err(_) => None,
    };
}

pub fn is_ready() -> bool {
    let slot = match INTERPOLATOR.lock() {
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

/// The frame `t` of the way from `a` to `b`, where t is 0..1.
///
/// Returns `None` when no model is loaded, so a caller can fall back to simply
/// not adding the frame rather than failing.
pub fn between(a: &Array3<u8>, b: &Array3<u8>, t: f32) -> Option<Array3<u8>> {
    let mut slot = match INTERPOLATOR.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    let interpolator = slot.as_mut()?;

    let (h, w, _) = a.dim();
    if b.dim() != (h, w, 3) || h == 0 || w == 0 {
        return None;
    }

    let to_input = |image: &Array3<u8>| {
        let mut tensor = Array4::<f32>::zeros((1, 3, h, w));
        for y in 0..h {
            for x in 0..w {
                for c in 0..3 {
                    // RIFE works on 0..1 RGB, no normalisation.
                    tensor[[0, c, y, x]] = image[[y, x, c]] as f32 / 255.0;
                }
            }
        }
        tensor
    };

    let first = to_input(a);
    let second = to_input(b);

    // Find the two image inputs by name where they are named, by position where
    // they are not.
    let inputs_list = interpolator.session.inputs();
    let name_of = |wanted: &str| {
        inputs_list
            .iter()
            .find(|i| i.name().to_ascii_lowercase().contains(wanted))
            .map(|i| i.name().to_string())
    };
    let first_name = name_of("img0")
        .or_else(|| name_of("img1").filter(|_| false))
        .or_else(|| inputs_list.first().map(|i| i.name().to_string()))?;
    let second_name = name_of("img1")
        .or_else(|| inputs_list.get(1).map(|i| i.name().to_string()))?;

    let output_name = interpolator
        .session
        .outputs()
        .first()
        .map(|o| o.name().to_string())?;

    // A third input means the export wants an explicit moment. A two-input
    // export implies the midpoint, so passing one would be wrong.
    let timestep_name = inputs_list
        .iter()
        .find(|i| {
            let n = i.name().to_ascii_lowercase();
            n.contains("time") || n.contains("t") && n.len() <= 2
        })
        .map(|i| i.name().to_string());

    let run = if let Some(time_name) = timestep_name {
        let time = ndarray::Array1::<f32>::from_vec(vec![t.clamp(0.0, 1.0)]);
        interpolator.session.run(inputs![
            first_name.as_str() => TensorRef::from_array_view(first.view()).ok()?,
            second_name.as_str() => TensorRef::from_array_view(second.view()).ok()?,
            time_name.as_str() => TensorRef::from_array_view(time.view()).ok()?,
        ])
    } else {
        interpolator.session.run(inputs![
            first_name.as_str() => TensorRef::from_array_view(first.view()).ok()?,
            second_name.as_str() => TensorRef::from_array_view(second.view()).ok()?,
        ])
    }
    .ok()?;

    let raw = run[output_name.as_str()].try_extract_array::<f32>().ok()?;
    let shape = raw.shape().to_vec();
    if shape.len() != 4 || shape[1] != 3 {
        return None;
    }
    let (oh, ow) = (shape[2], shape[3]);

    let mut out = Array3::<u8>::zeros((oh, ow, 3));
    for y in 0..oh {
        for x in 0..ow {
            for c in 0..3 {
                out[[y, x, c]] = (raw[[0, c, y, x]] * 255.0).clamp(0.0, 255.0) as u8;
            }
        }
    }
    Some(out)
}
