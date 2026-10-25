//! Inpainting: fill a region the user painted over.
//!
//! This is the one runner that needs TWO inputs, and that is the whole reason it
//! exists separately. LaMa, MI-GAN and AOT-GAN all take an image and a mask, and
//! the generic image2image runner feeds a single tensor - so a masked fill was
//! impossible no matter which model was loaded.
//!
//! The mask arrives in the ALPHA channel of the same RGBA buffer the other tools
//! receive. That avoids widening the JNI signature for one runner: the caller
//! paints a mask into alpha, and the pixels it painted are the pixels to fill.
//!
//! The working method follows MI-GAN's published pipeline: crop around the hole,
//! run at the model's native size, then paste back ONLY the masked pixels at full
//! resolution. Everything outside the mask is the original image, untouched - which
//! matters, because a 512px inpaint stretched back over a 12-megapixel photo would
//! soften the whole frame to fix one corner of it.

use crate::types::{FaceSwapError, Result};
use ndarray::{Array3, Array4};
use ort::inputs;
use ort::session::Session;
use ort::value::TensorRef;

/// The size these models are trained at. LaMa, MI-GAN and AOT-GAN all use it.
const WORK: usize = 512;

/// How much context around the hole to give the model, as a fraction of the hole.
const CONTEXT: f32 = 0.35;

/// Bilinear resize, written out rather than pulled in, so this module has no
/// dependency the rest of the tool layer does not already have.
fn resize(src: &Array3<u8>, w: usize, h: usize) -> Array3<u8> {
    let (sh, sw, sc) = src.dim();
    let mut out = Array3::<u8>::zeros((h, w, sc));
    if sh == 0 || sw == 0 || h == 0 || w == 0 {
        return out;
    }
    let xr = (sw as f32 - 1.0) / (w as f32).max(1.0);
    let yr = (sh as f32 - 1.0) / (h as f32).max(1.0);
    for y in 0..h {
        let sy = y as f32 * yr;
        let y0 = sy.floor() as usize;
        let y1 = (y0 + 1).min(sh - 1);
        let fy = sy - y0 as f32;
        for x in 0..w {
            let sx = x as f32 * xr;
            let x0 = sx.floor() as usize;
            let x1 = (x0 + 1).min(sw - 1);
            let fx = sx - x0 as f32;
            for c in 0..sc {
                let a = src[[y0, x0, c]] as f32 * (1.0 - fx) + src[[y0, x1, c]] as f32 * fx;
                let b = src[[y1, x0, c]] as f32 * (1.0 - fx) + src[[y1, x1, c]] as f32 * fx;
                out[[y, x, c]] = (a * (1.0 - fy) + b * fy).round().clamp(0.0, 255.0) as u8;
            }
        }
    }
    out
}

/// The bounding box of everything the user painted, in source pixels.
///
/// Returns `(x0, y0, x1, y1)` half-open. `None` when nothing was painted, which
/// is a legitimate state: the tool then returns the image unchanged rather than
/// running a model over nothing.
fn painted_box(rgba: &Array3<u8>) -> Option<(usize, usize, usize, usize)> {
    let (h, w, c) = rgba.dim();
    if c < 4 {
        return None;
    }
    let mut x0 = w;
    let mut y0 = h;
    let mut x1 = 0usize;
    let mut y1 = 0usize;
    let mut found = false;
    for y in 0..h {
        for x in 0..w {
            // Anything at or above the midpoint counts as painted. The caller
            // paints with a solid brush, so this is a threshold, not a guess.
            if rgba[[y, x, 3]] >= 128 {
                found = true;
                x0 = x0.min(x);
                y0 = y0.min(y);
                x1 = x1.max(x + 1);
                y1 = y1.max(y + 1);
            }
        }
    }
    if found {
        Some((x0, y0, x1, y1))
    } else {
        None
    }
}

/// Fill the painted region.
pub fn run(session: &mut Session, rgba: &Array3<u8>) -> Result<Array3<u8>> {
    let (h, w, c) = rgba.dim();
    if h == 0 || w == 0 {
        return Err(FaceSwapError::InvalidInput("the image is empty".to_string()));
    }
    if c < 4 {
        return Err(FaceSwapError::InvalidInput(
            "inpainting needs a mask in the alpha channel".to_string(),
        ));
    }

    let (bx0, by0, bx1, by1) = match painted_box(rgba) {
        Some(b) => b,
        // Nothing painted: hand the image back rather than inventing a region.
        None => {
            let mut out = Array3::<u8>::zeros((h, w, 3));
            for y in 0..h {
                for x in 0..w {
                    for ch in 0..3 {
                        out[[y, x, ch]] = rgba[[y, x, ch]];
                    }
                }
            }
            return Ok(out);
        }
    };

    // Give the model context around the hole, then square it up - these networks
    // expect a square input and do better when the hole is not flush to an edge.
    let bw = (bx1 - bx0) as f32;
    let bh = (by1 - by0) as f32;
    let pad_x = (bw * CONTEXT) as usize + 8;
    let pad_y = (bh * CONTEXT) as usize + 8;
    let cx0 = bx0.saturating_sub(pad_x);
    let cy0 = by0.saturating_sub(pad_y);
    let cx1 = (bx1 + pad_x).min(w);
    let cy1 = (by1 + pad_y).min(h);

    let cw = cx1 - cx0;
    let ch = cy1 - cy0;
    let side = cw.max(ch).min(WORK.max(cw).max(ch));

    let mut crop = Array3::<u8>::zeros((ch, cw, 3));
    let mut mask = Array3::<u8>::zeros((ch, cw, 1));
    for y in 0..ch {
        for x in 0..cw {
            for ch3 in 0..3 {
                crop[[y, x, ch3]] = rgba[[cy0 + y, cx0 + x, ch3]];
            }
            // Outside the painted box the mask is zero, so the model is told the
            // context is trustworthy and the hole is the only thing to invent.
            let inside = cy0 + y >= by0 && cy0 + y < by1 && cx0 + x >= bx0 && cx0 + x < bx1;
            mask[[y, x, 0]] = if inside && rgba[[cy0 + y, cx0 + x, 3]] >= 128 { 1 } else { 0 };
        }
    }
    let _ = side;

    // Square-pad to a square, then to the model's size.
    let sq = cw.max(ch);
    let mut sq_img = Array3::<u8>::zeros((sq, sq, 3));
    let mut sq_mask = Array3::<u8>::zeros((sq, sq, 1));
    let ox = (sq - cw) / 2;
    let oy = (sq - ch) / 2;
    for y in 0..ch {
        for x in 0..cw {
            for ch3 in 0..3 {
                sq_img[[oy + y, ox + x, ch3]] = crop[[y, x, ch3]];
            }
            sq_mask[[oy + y, ox + x, 0]] = mask[[y, x, 0]];
        }
    }

    let img_work = resize(&sq_img, WORK, WORK);
    let mask_work = resize(&sq_mask, WORK, WORK);

    // LaMa and MI-GAN both take the image in [0,1] and the mask as 0/1, NCHW.
    let mut image_arr = Array4::<f32>::zeros((1, 3, WORK, WORK));
    let mut mask_arr = Array4::<f32>::zeros((1, 1, WORK, WORK));
    for c3 in 0..3 {
        for y in 0..WORK {
            for x in 0..WORK {
                image_arr[[0, c3, y, x]] = img_work[[y, x, c3]] as f32 / 255.0;
            }
        }
    }
    for y in 0..WORK {
        for x in 0..WORK {
            mask_arr[[0, 0, y, x]] = if mask_work[[y, x, 0]] >= 128 { 1.0 } else { 0.0 };
        }
    }

    // Feed by declared order: the first input is the image, the second the mask.
    // Taking the names from the session keeps this working across exports that
    // call them "image"/"mask", "img"/"mask" or anything else.
    let names: Vec<String> = session.inputs().iter().map(|i| i.name().to_string()).collect();
    if names.len() < 2 {
        return Err(FaceSwapError::ProcessingError(format!(
            "this model has {} input(s); inpainting needs an image and a mask",
            names.len()
        )));
    }
    let out_name = session
        .outputs()
        .first()
        .map(|o| o.name().to_string())
        .ok_or_else(|| FaceSwapError::Ort("the model declares no outputs".to_string()))?;

    let outputs = session
        .run(inputs![
            names[0].as_str() => TensorRef::from_array_view(image_arr.view())?,
            names[1].as_str() => TensorRef::from_array_view(mask_arr.view())?,
        ])
        .map_err(|e| FaceSwapError::Ort(format!("inpaint run: {e}")))?;

    let out = outputs[out_name.as_str()]
        .try_extract_array::<f32>()
        .map_err(|e| FaceSwapError::Ort(format!("output: {e}")))?
        .into_owned();
    let shape = out.shape().to_vec();
    if shape.len() != 4 || shape[1] != 3 {
        return Err(FaceSwapError::ProcessingError(format!(
            "unexpected output shape: {shape:?}"
        )));
    }
    let (oh, ow) = (shape[2], shape[3]);

    // Back to a square RGB image at the model's output size, then to the crop's.
    let mut filled = Array3::<u8>::zeros((oh, ow, 3));
    for y in 0..oh {
        for x in 0..ow {
            for c3 in 0..3 {
                let v = out[[0, c3, y, x]];
                filled[[y, x, c3]] = (v.clamp(0.0, 1.0) * 255.0).round() as u8;
            }
        }
    }
    let back = resize(&filled, sq, sq);

    // Paste ONLY what was painted. Every other pixel is the original, so a fill
    // cannot soften or recolour any part of the photo the user did not mark.
    let mut out = Array3::<u8>::zeros((h, w, 3));
    for y in 0..h {
        for x in 0..w {
            for c3 in 0..3 {
                out[[y, x, c3]] = rgba[[y, x, c3]];
            }
        }
    }
    for y in 0..ch {
        for x in 0..cw {
            if mask[[y, x, 0]] == 0 {
                continue;
            }
            let sy = oy + y;
            let sx = ox + x;
            if sy >= sq || sx >= sq {
                continue;
            }
            for c3 in 0..3 {
                out[[cy0 + y, cx0 + x, c3]] = back[[sy, sx, c3]];
            }
        }
    }

    Ok(out)
}
