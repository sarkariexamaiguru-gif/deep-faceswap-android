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
use crate::types::BBox;
use ndarray::{s, Array3, Array4};
use std::sync::Mutex;
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
        .with_optimization_level(GraphOptimizationLevel::Level1)
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

// ---------------------------------------------------------------- the slot --

/// One XSeg, loaded once and shared.
///
/// A process-wide slot rather than a parameter, for the same reason
/// `config::intra_threads` is one: threading an occluder through every swap
/// signature and its three call layers buys nothing, and the model is identical
/// for every caller. The Mutex is not decoration - clone workers run in
/// parallel, so two frames can arrive at once.
static OCCLUDER: Mutex<Option<Occluder>> = Mutex::new(None);

struct Occluder {
    path: String,
    session: Session,
}

/// Point occlusion at a model. An empty path turns it off.
///
/// A model that will not load turns it off too, rather than failing every frame
/// from then on - occlusion is an improvement, not a prerequisite.
pub fn set_model_path(path: &str) {
    let mut slot = match OCCLUDER.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    if path.is_empty() {
        *slot = None;
        return;
    }
    if slot.as_ref().map(|o| o.path == path).unwrap_or(false) {
        return;
    }
    *slot = match load(path) {
        Ok(session) => Some(Occluder {
            path: path.to_string(),
            session,
        }),
        Err(_) => None,
    };
}

/// Whether an occluder is loaded and ready.
pub fn is_ready() -> bool {
    let slot = match OCCLUDER.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    slot.is_some()
}

/// How far past the detected box to look, so an object at the face's edge is
/// still caught. The reference default.
const PADDING: f32 = 0.15;

/// The pixels we must not overwrite, and where they are.
pub struct OcclusionData {
    x: usize,
    y: usize,
    original: Array3<u8>,
    mask: Array3<u8>,
}

/// Work out what is in front of the face, and keep the original pixels.
///
/// Must run BEFORE the swap, because afterwards the pixels are gone.
pub fn prepare(image: &Array3<u8>, bbox: &BBox) -> Option<OcclusionData> {
    let mut slot = match OCCLUDER.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    let occluder = slot.as_mut()?;

    let (h, w, _) = image.dim();
    let pad_x = (bbox.x2 - bbox.x1) * PADDING;
    let pad_y = (bbox.y2 - bbox.y1) * PADDING;

    let x0 = (bbox.x1 - pad_x).floor().max(0.0) as usize;
    let y0 = (bbox.y1 - pad_y).floor().max(0.0) as usize;
    let x1 = ((bbox.x2 + pad_x).ceil() as usize).min(w);
    let y1 = ((bbox.y2 + pad_y).ceil() as usize).min(h);
    if x1 <= x0 || y1 <= y0 || x1 > w || y1 > h {
        return None;
    }

    let original = image.slice(s![y0..y1, x0..x1, ..]).to_owned();
    let mut mask = mask_for(&mut occluder.session, &original).ok()?;

    // Geometry, on top of pixels. Where an object is skin-coloured - a hand, a
    // forearm - XSeg can be fooled into calling it face. HRFFA looks at the
    // landmarks instead and says which of them are behind something, which is a
    // signal colour cannot fake.
    //
    // Used to TIGHTEN only: a hidden point clears a small disc in the mask, so
    // the worst this can do is leave a little more of the original alone.
    if crate::tools::landmarks::is_ready() {
        if let Some(points) = crate::tools::landmarks::predict(image, bbox) {
            let (mh, mw, _) = mask.dim();
            let radius = crate::tools::landmarks::protect_radius(mw, mh);
            for point in points.iter().filter(|p| p.occluded) {
                let px = point.x.round() as isize - x0 as isize;
                let py = point.y.round() as isize - y0 as isize;
                for dy in -radius..=radius {
                    for dx in -radius..=radius {
                        if dx * dx + dy * dy > radius * radius {
                            continue;
                        }
                        let tx = px + dx;
                        let ty = py + dy;
                        if tx < 0 || ty < 0 || tx as usize >= mw || ty as usize >= mh {
                            continue;
                        }
                        // Low means "not skin", which means "leave it alone".
                        mask[[ty as usize, tx as usize, 0]] = 0;
                    }
                }
            }
        }
    }
    Some(OcclusionData {
        x: x0,
        y: y0,
        original,
        mask,
    })
}

/// Put the original pixels back wherever something is covering the face.
///
/// This is the whole point. A swapper is pixel-level and would otherwise paint
/// the new face straight over the microphone.
pub fn restore(image: &mut Array3<u8>, data: &OcclusionData) {
    let (mh, mw, _) = data.mask.dim();
    let (ih, iw, _) = image.dim();

    // Quality: how much of this face was behind something. Counted over the
    // whole crop, so it is a fraction of the face box rather than of the skin.
    let mut protected = 0usize;
    let mut considered = 0usize;

    for y in 0..mh {
        let ty = data.y + y;
        if ty >= ih {
            break;
        }
        for x in 0..mw {
            let tx = data.x + x;
            if tx >= iw {
                break;
            }
            let skin = data.mask[[y, x, 0]] as f32 / 255.0;
            // High means skin, so invert. The knee at 0.5 with a short ramp
            // gives a near-binary decision with a soft join, which is what
            // stops the restored object showing a hard outline.
            let keep = ((0.5 - skin) * 4.0).clamp(0.0, 1.0);
            considered += 1;
            if keep > 0.5 {
                protected += 1;
            }
            if keep <= 0.01 {
                continue;
            }
            for c in 0..3 {
                let now = image[[ty, tx, c]] as f32;
                let before = data.original[[y, x, c]] as f32;
                image[[ty, tx, c]] =
                    (now * (1.0 - keep) + before * keep).clamp(0.0, 255.0) as u8;
            }
        }
    }

    if considered > 0 {
        crate::tools::quality::record_occlusion(protected as f64 / considered as f64);
    }
}
