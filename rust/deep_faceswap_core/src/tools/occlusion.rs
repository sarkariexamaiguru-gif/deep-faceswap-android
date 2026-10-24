//! XSeg: what is in front of the face.
//!
//! The trap this exists to close: swap a face and watch the microphone, the
//! hand, or the sunglasses get painted over with forehead. A swapper is
//! pixel-level and has no idea something is covering the face.
//!
//! XSeg is DeepFaceLab's face-occlusion model. Given a face crop it returns a
//! mask that is high where the model sees face skin and low where something is
//! in front of it. The swap then leaves the low pixels alone.
//!
//! Contract taken from the reference implementation rather than guessed:
//! input `input`, float32 `[1, 3, 256, 256]`, scaled 0-1, **BGR** - DeepFaceLab
//! trains through OpenCV, which is BGR, and the reference does not reverse the
//! channels. Output is already a 0-1 mask, not a logit, so there is no sigmoid
//! here - unlike the segmentation runner, which needs one.

use crate::config;
use crate::types::{FaceSwapError, Result};
use ndarray::{Array3, Array4};
use ort::{
    inputs,
    session::{builder::GraphOptimizationLevel, Session},
    value::TensorRef,
};

/// XSeg is fixed at 256 regardless of which variant.
const SIZE: usize = 256;

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

/// The face-skin mask for one crop, at the crop's own size.
///
/// High means skin, low means occluded. The caller keeps the original pixels
/// wherever this is low.
pub fn mask_for(session: &mut Session, crop: &Array3<u8>) -> Result<Array3<u8>> {
    let (h, w, _) = crop.dim();
    if h == 0 || w == 0 {
        return Err(FaceSwapError::InvalidInput("the crop is empty".to_string()));
    }

    let small = resize(crop, SIZE, SIZE);
    let mut input = Array4::<f32>::zeros((1, 3, SIZE, SIZE));
    for y in 0..SIZE {
        for x in 0..SIZE {
            // BGR, because that is what the reference feeds it.
            input[[0, 0, y, x]] = small[[y, x, 2]] as f32 / 255.0;
            input[[0, 1, y, x]] = small[[y, x, 1]] as f32 / 255.0;
            input[[0, 2, y, x]] = small[[y, x, 0]] as f32 / 255.0;
        }
    }

    // The reference names it `input`; fall back to whatever it declares.
    let input_name = session
        .inputs()
        .iter()
        .find(|i| i.name() == "input")
        .or_else(|| session.inputs().first())
        .map(|i| i.name().to_string())
        .ok_or_else(|| FaceSwapError::Ort("the model declares no inputs".to_string()))?;
    let output_name = session
        .outputs()
        .first()
        .map(|o| o.name().to_string())
        .ok_or_else(|| FaceSwapError::Ort("the model declares no outputs".to_string()))?;

    let outputs = session
        .run(inputs![input_name.as_str() => TensorRef::from_array_view(input.view())?])
        .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?;

    let raw = outputs[output_name.as_str()]
        .try_extract_array::<f32>()
        .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
        .into_owned();

    let shape = raw.shape().to_vec();
    let (mh, mw) = match shape.len() {
        4 => (shape[2], shape[3]),
        3 => (shape[1], shape[2]),
        _ => {
            return Err(FaceSwapError::ProcessingError(format!(
                "unexpected mask shape: {:?}",
                shape
            )))
        }
    };

    let mut mask = Array3::<u8>::zeros((mh, mw, 1));
    for y in 0..mh {
        for x in 0..mw {
            let value = match shape.len() {
                4 => raw[[0, 0, y, x]],
                _ => raw[[0, y, x]],
            };
            // Already 0-1. No sigmoid.
            mask[[y, x, 0]] = (value.clamp(0.0, 1.0) * 255.0) as u8;
        }
    }

    // Back to the crop's size, so the caller can index it directly.
    Ok(resize_mask(&mask, w, h))
}

fn resize(image: &Array3<u8>, new_w: usize, new_h: usize) -> Array3<u8> {
    let (h, w, _) = image.dim();
    let mut out = Array3::<u8>::zeros((new_h, new_w, 3));
    if new_w == 0 || new_h == 0 || w == 0 || h == 0 {
        return out;
    }
    let x_ratio = (w - 1) as f32 / (new_w.max(2) - 1) as f32;
    let y_ratio = (h - 1) as f32 / (new_h.max(2) - 1) as f32;
    for y in 0..new_h {
        let sy = y as f32 * y_ratio;
        let y0 = (sy.floor() as usize).min(h - 1);
        let y1 = (y0 + 1).min(h - 1);
        let fy = sy - y0 as f32;
        for x in 0..new_w {
            let sx = x as f32 * x_ratio;
            let x0 = (sx.floor() as usize).min(w - 1);
            let x1 = (x0 + 1).min(w - 1);
            let fx = sx - x0 as f32;
            for c in 0..3 {
                let top = image[[y0, x0, c]] as f32 * (1.0 - fx) + image[[y0, x1, c]] as f32 * fx;
                let bottom =
                    image[[y1, x0, c]] as f32 * (1.0 - fx) + image[[y1, x1, c]] as f32 * fx;
                out[[y, x, c]] = (top * (1.0 - fy) + bottom * fy).clamp(0.0, 255.0) as u8;
            }
        }
    }
    out
}

fn resize_mask(mask: &Array3<u8>, new_w: usize, new_h: usize) -> Array3<u8> {
    let (h, w, _) = mask.dim();
    let mut out = Array3::<u8>::zeros((new_h, new_w, 1));
    if new_w == 0 || new_h == 0 || w == 0 || h == 0 {
        return out;
    }
    let x_ratio = (w - 1) as f32 / (new_w.max(2) - 1) as f32;
    let y_ratio = (h - 1) as f32 / (new_h.max(2) - 1) as f32;
    for y in 0..new_h {
        let sy = y as f32 * y_ratio;
        let y0 = (sy.floor() as usize).min(h - 1);
        let y1 = (y0 + 1).min(h - 1);
        let fy = sy - y0 as f32;
        for x in 0..new_w {
            let sx = x as f32 * x_ratio;
            let x0 = (sx.floor() as usize).min(w - 1);
            let x1 = (x0 + 1).min(w - 1);
            let fx = sx - x0 as f32;
            let top = mask[[y0, x0, 0]] as f32 * (1.0 - fx) + mask[[y0, x1, 0]] as f32 * fx;
            let bottom = mask[[y1, x0, 0]] as f32 * (1.0 - fx) + mask[[y1, x1, 0]] as f32 * fx;
            out[[y, x, 0]] = (top * (1.0 - fy) + bottom * fy).clamp(0.0, 255.0) as u8;
        }
    }
    out
}
