//! One-to-one models: denoise, de-JPEG, and anything else that returns an image
//! the same size it was given.
//!
//! This is the second shape a tool can have, after the upscaler. Where the
//! upscaler is 4x and lives in its own module, these are all 1x and differ only
//! in which weights they loaded — so one runner covers the whole family, and
//! adding a denoiser is a registry entry rather than a module.
//!
//! Contract, taken from the reference implementations rather than assumed:
//! input float32 `[1, 3, h, w]`, RGB scaled 0-1, NCHW; output the same shape,
//! same range. Tiled with a halo, because these are fully convolutional and a
//! phone cannot hold a 12 megapixel intermediate.

use crate::config;
use crate::types::{FaceSwapError, Result};
use ndarray::{s, Array3, Array4};
use ort::{
    inputs,
    session::{builder::GraphOptimizationLevel, Session},
    value::TensorRef,
};

/// Core tile size, in pixels.
const TILE: usize = 512;

/// How far each tile reaches past its core, so the model never sees a border
/// the image does not have. Seams are the classic failure of tiled processing.
const HALO: usize = 16;

/// Load the model.
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

/// Run a 1x model over an RGB image.
pub fn run(session: &mut Session, image: &Array3<u8>) -> Result<Array3<u8>> {
    let (h, w, _) = image.dim();
    if h == 0 || w == 0 {
        return Err(FaceSwapError::InvalidInput("the image is empty".to_string()));
    }

    let mut out = Array3::<u8>::zeros((h, w, 3));

    let mut core_y = 0;
    while core_y < h {
        let core_y1 = (core_y + TILE).min(h);
        let mut core_x = 0;
        while core_x < w {
            let core_x1 = (core_x + TILE).min(w);

            let read_y0 = core_y.saturating_sub(HALO);
            let read_y1 = (core_y1 + HALO).min(h);
            let read_x0 = core_x.saturating_sub(HALO);
            let read_x1 = (core_x1 + HALO).min(w);

            let tile = image
                .slice(s![read_y0..read_y1, read_x0..read_x1, ..])
                .to_owned();
            let cleaned = run_once(session, &tile)?;

            // A 1x model must hand back what it was given. If it did not, say so
            // rather than copying a mismatched region and producing nonsense.
            let (ch, cw, _) = cleaned.dim();
            if ch != read_y1 - read_y0 || cw != read_x1 - read_x0 {
                return Err(FaceSwapError::ProcessingError(format!(
                    "this model returned {cw}x{ch} for a {}x{} tile — it is not a 1x model",
                    read_x1 - read_x0,
                    read_y1 - read_y0,
                )));
            }

            let off_y = core_y - read_y0;
            let off_x = core_x - read_x0;
            let span_y = core_y1 - core_y;
            let span_x = core_x1 - core_x;

            for y in 0..span_y {
                for x in 0..span_x {
                    for c in 0..3 {
                        out[[core_y + y, core_x + x, c]] = cleaned[[off_y + y, off_x + x, c]];
                    }
                }
            }

            if core_x1 == w {
                break;
            }
            core_x += TILE;
        }
        if core_y1 == h {
            break;
        }
        core_y += TILE;
    }

    Ok(out)
}

fn run_once(session: &mut Session, tile: &Array3<u8>) -> Result<Array3<u8>> {
    let (h, w, _) = tile.dim();

    let mut input = Array4::<f32>::zeros((1, 3, h, w));
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                input[[0, c, y, x]] = tile[[y, x, c]] as f32 / 255.0;
            }
        }
    }

    let input_name = session
        .inputs()
        .first()
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

    let out = outputs[output_name.as_str()]
        .try_extract_array::<f32>()
        .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
        .into_owned();

    let shape = out.shape().to_vec();
    if shape.len() != 4 || shape[1] != 3 {
        return Err(FaceSwapError::ProcessingError(format!(
            "unexpected output shape: {:?}",
            shape
        )));
    }
    let (out_h, out_w) = (shape[2], shape[3]);

    let mut rgb = Array3::<u8>::zeros((out_h, out_w, 3));
    for y in 0..out_h {
        for x in 0..out_w {
            for c in 0..3 {
                rgb[[y, x, c]] = (out[[0, c, y, x]] * 255.0).clamp(0.0, 255.0) as u8;
            }
        }
    }

    Ok(rgb)
}
