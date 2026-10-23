//! Segmentation: BiRefNet, for cutting a subject out of its background.
//!
//! The third shape a tool can have. Where the upscaler returns a bigger image
//! and the 1x family returns the same image, this returns a *mask* — so the
//! runner owns the whole job: resize to what the model wants, normalise the way
//! the reference implementation does, take the sigmoid of the logits, and hand
//! back RGBA with the mask as alpha.
//!
//! Contract taken from the model card and the reference inference code rather
//! than guessed: input `input_image` float32 `[1, 3, 512, 512]`, RGB divided by
//! 255 then normalised with the ImageNet mean and standard deviation; output a
//! single-channel logit map, `sigmoid`-ed to a matte.

use crate::config;
use crate::types::{FaceSwapError, Result};
use ndarray::{Array3, Array4};
use ort::{
    inputs,
    session::{builder::GraphOptimizationLevel, Session},
    value::TensorRef,
};

/// What this family expects. The lite-512 export is fixed at 512.
const SIZE: usize = 512;

const MEAN: [f32; 3] = [0.485, 0.456, 0.406];
const STD: [f32; 3] = [0.229, 0.224, 0.225];

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

/// Cut the subject out. Returns RGBA at the original size.
pub fn matte(session: &mut Session, image: &Array3<u8>) -> Result<Array3<u8>> {
    let (h, w, _) = image.dim();
    if h == 0 || w == 0 {
        return Err(FaceSwapError::InvalidInput("the image is empty".to_string()));
    }

    // ---- to the model's size, in its range
    let small = resize_rgb(image, SIZE, SIZE);
    let mut input = Array4::<f32>::zeros((1, 3, SIZE, SIZE));
    for y in 0..SIZE {
        for x in 0..SIZE {
            for c in 0..3 {
                let value = small[[y, x, c]] as f32 / 255.0;
                input[[0, c, y, x]] = (value - MEAN[c]) / STD[c];
            }
        }
    }

    let input_name = session
        .inputs()
        .first()
        .map(|i| i.name().to_string())
        .ok_or_else(|| FaceSwapError::Ort("the model declares no inputs".to_string()))?;

    // Prefer the documented output by name; a re-export is free to rename it.
    let output_name = session
        .outputs()
        .iter()
        .find(|o| o.name().contains("output_image"))
        .or_else(|| session.outputs().first())
        .map(|o| o.name().to_string())
        .ok_or_else(|| FaceSwapError::Ort("the model declares no outputs".to_string()))?;

    let outputs = session
        .run(inputs![input_name.as_str() => TensorRef::from_array_view(input.view())?])
        .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?;

    let logits = outputs[output_name.as_str()]
        .try_extract_array::<f32>()
        .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
        .into_owned();

    let shape = logits.shape().to_vec();
    // Accept [1,1,S,S], [1,S,S] or [S,S] — the exports differ.
    let mask_small = match shape.len() {
        4 => {
            let (mh, mw) = (shape[2], shape[3]);
            let mut m = Array3::<f32>::zeros((mh, mw, 1));
            for y in 0..mh {
                for x in 0..mw {
                    m[[y, x, 0]] = logits[[0, 0, y, x]];
                }
            }
            m
        }
        3 => {
            let (mh, mw) = (shape[1], shape[2]);
            let mut m = Array3::<f32>::zeros((mh, mw, 1));
            for y in 0..mh {
                for x in 0..mw {
                    m[[y, x, 0]] = logits[[0, y, x]];
                }
            }
            m
        }
        _ => {
            return Err(FaceSwapError::ProcessingError(format!(
                "unexpected mask shape: {:?}",
                shape
            )))
        }
    };

    // ---- sigmoid, then back to the original size
    let (mh, mw, _) = mask_small.dim();
    let mut mask = Array3::<u8>::zeros((mh, mw, 1));
    for y in 0..mh {
        for x in 0..mw {
            let logit = mask_small[[y, x, 0]];
            let p = 1.0 / (1.0 + (-logit).exp());
            mask[[y, x, 0]] = (p.clamp(0.0, 1.0) * 255.0) as u8;
        }
    }
    let full_mask = resize_mask(&mask, w, h);

    // ---- RGBA: the original colours, the mask as alpha
    let mut out = Array3::<u8>::zeros((h, w, 4));
    for y in 0..h {
        for x in 0..w {
            out[[y, x, 0]] = image[[y, x, 0]];
            out[[y, x, 1]] = image[[y, x, 1]];
            out[[y, x, 2]] = image[[y, x, 2]];
            out[[y, x, 3]] = full_mask[[y, x, 0]];
        }
    }
    Ok(out)
}

/// Bilinear RGB resize.
fn resize_rgb(image: &Array3<u8>, new_w: usize, new_h: usize) -> Array3<u8> {
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

/// Bilinear single-channel resize, for the mask.
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
