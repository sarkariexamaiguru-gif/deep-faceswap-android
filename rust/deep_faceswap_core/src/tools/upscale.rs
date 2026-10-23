//! Real-ESRGAN general x4v3 (SRVGGNetCompact).
//!
//! Contract taken from the model card rather than guessed:
//! input `input` float32 `[1, 3, h, w]`, RGB scaled 0-1, NCHW;
//! output `output` float32 `[1, 3, 4h, 4w]`; opset 17; height and width dynamic.
//! The graph does not clamp its output, so the u8 conversion below does.

use crate::config;
use crate::types::{FaceSwapError, Result};
use ndarray::{s, Array3, Array4};
use ort::{
    inputs,
    session::{builder::GraphOptimizationLevel, Session},
    value::TensorRef,
};

/// Core tile size, in input pixels.
const TILE: usize = 256;

/// How far each tile reaches past its core.
///
/// This is the whole reason tiled upscaling usually looks wrong: run tiles edge
/// to edge and the model invents a border at every seam. Giving each tile a halo
/// means the model never sees an edge the image does not have, and only the core
/// of the result is kept.
const HALO: usize = 16;

/// This family is 4x.
const SCALE: usize = 4;

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

/// Upscale an RGB image 4x.
pub fn run(session: &mut Session, image: &Array3<u8>) -> Result<Array3<u8>> {
    let (h, w, _) = image.dim();
    if h == 0 || w == 0 {
        return Err(FaceSwapError::InvalidInput("the image is empty".to_string()));
    }

    let mut out = Array3::<u8>::zeros((h * SCALE, w * SCALE, 3));

    let mut core_y = 0;
    while core_y < h {
        let core_y1 = (core_y + TILE).min(h);
        let mut core_x = 0;
        while core_x < w {
            let core_x1 = (core_x + TILE).min(w);

            // The tile, expanded by the halo and clamped to the image.
            let read_y0 = core_y.saturating_sub(HALO);
            let read_y1 = (core_y1 + HALO).min(h);
            let read_x0 = core_x.saturating_sub(HALO);
            let read_x1 = (core_x1 + HALO).min(w);

            let tile = image
                .slice(s![read_y0..read_y1, read_x0..read_x1, ..])
                .to_owned();
            let up = run_once(session, &tile)?;

            // Where the core sits inside this tile's output.
            let off_y = (core_y - read_y0) * SCALE;
            let off_x = (core_x - read_x0) * SCALE;
            let span_y = (core_y1 - core_y) * SCALE;
            let span_x = (core_x1 - core_x) * SCALE;

            for y in 0..span_y {
                for x in 0..span_x {
                    for c in 0..3 {
                        out[[core_y * SCALE + y, core_x * SCALE + x, c]] =
                            up[[off_y + y, off_x + x, c]];
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

/// One pass of the model over a tile.
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

    // Take the declared names rather than assuming them: the contract is
    // documented, but a re-export is free to name its tensors differently.
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
