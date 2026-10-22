//! Face swapping using inswapper_128 and HyperSwap 256
//!
//! Two swapper families are supported. They share the same ONNX session setup
//! and the same `source` / `target` input contract, but differ in how identity
//! is supplied and how pixels are scaled:
//!
//! ## inswapper_128 (InsightFace)
//! - Model: inswapper_128.onnx
//! - Input size: 128x128 aligned face images
//! - Source: 512-dimensional ArcFace embedding, projected through an `emap`
//!   matrix and re-normalized
//! - Target/output range: [0, 1] (mean 0, std 1)
//!
//! ## HyperSwap 256 (FaceFusion)
//! - Model: hyperswap_1a_256.onnx / hyperswap_1c_256.onnx
//! - Input size: 256x256 aligned face images
//! - Source: the **L2-normalized** 512-dimensional ArcFace embedding, used
//!   directly — no converter matrix is involved for this family
//! - Target range: [-1, 1] (mean 0.5, std 0.5); output is mapped back to [0, 1]
//!
//! # Emap transformation (inswapper only)
//! inswapper requires an emap (embedding map) matrix (512x512) that transforms
//! the source embedding into the model's latent space. This matrix must be
//! extracted from the ONNX model file separately and saved as a binary file.
//! HyperSwap does not use one.

use crate::types::{FaceSwapError, Result};
use crate::verbose::{get_verbose_level, VerboseLevel, EVENT_LOAD_MODEL, EVENT_SWAP_FACE};
use crate::{log_additional, log_main};
use ndarray::{Array1, Array2, Array4};
use ort::{
    inputs,
    logging::LogLevel,
    session::{builder::GraphOptimizationLevel, Session},
    value::TensorRef,
};
use std::path::Path;

#[cfg(feature = "cuda")]
use ort::execution_providers::CUDAExecutionProvider;

/// Native aligned-face size for inswapper.
const INSWAPPER_SIZE: usize = 128;
/// Native aligned-face size for HyperSwap.
const HYPERSWAP_SIZE: usize = 256;
/// Largest aligned face we will ever hand a swapper (matches the 1024 pixel
/// boost ceiling the desktop tools use).
pub const MAX_SWAP_SIZE: usize = 1024;

/// Which swapper family a model file belongs to.
///
/// Detected from the file name, which is reliable for the files this app
/// downloads and keeps the JNI surface unchanged.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SwapperKind {
    /// InsightFace inswapper_128 — emap-projected ArcFace embedding, 128px.
    Inswapper,
    /// FaceFusion HyperSwap — normalised ArcFace embedding, 256px.
    Hyperswap,
}

impl SwapperKind {
    /// Infer the family from a model path.
    pub fn from_path(path: &str) -> Self {
        if path.to_ascii_lowercase().contains("hyperswap") {
            Self::Hyperswap
        } else {
            Self::Inswapper
        }
    }

    /// Native aligned-face size for this family.
    pub fn model_size(self) -> usize {
        match self {
            Self::Inswapper => INSWAPPER_SIZE,
            Self::Hyperswap => HYPERSWAP_SIZE,
        }
    }
}

/// Face swapper model
///
/// Combines a target face image with a source face embedding to produce a
/// swapped face that keeps the target's pose/expression and adopts the source's
/// identity.
///
/// # Example
/// ```ignore
/// use deep_faceswap_core::swapper::FaceSwapper;
///
/// let mut swapper = FaceSwapper::new("models/inswapper_128.onnx")?;
/// let swapped = swapper.swap(&target_face, &source_embedding)?;
/// ```
pub struct FaceSwapper {
    /// ONNX Runtime session for the face swapping model
    session: Session,
    /// Which family this model belongs to
    kind: SwapperKind,
    /// Emap transformation matrix (512x512); inswapper only
    emap: Option<Array2<f32>>,
}

impl FaceSwapper {
    /// Load a face swapper, inferring the family from the file name.
    pub fn new(model_path: &str) -> Result<Self> {
        Self::new_with_kind(model_path, SwapperKind::from_path(model_path))
    }

    /// Load a face swapper of an explicitly chosen family.
    ///
    /// # Arguments
    /// * `model_path` - Path to the swapper ONNX file
    /// * `kind` - Swapper family; controls input size, embedding prep and scaling
    ///
    /// # Errors
    /// - `ModelNotFound` if the model file (or, for inswapper, the emap) is missing
    /// - `Ort` if ONNX Runtime initialization fails
    pub fn new_with_kind(model_path: &str, kind: SwapperKind) -> Result<Self> {
        if !Path::new(model_path).exists() {
            return Err(FaceSwapError::ModelNotFound(model_path.to_string()));
        }

        log_main!(
            EVENT_LOAD_MODEL,
            "Loading face swapper",
            path = model_path,
            size = kind.model_size()
        );

        // Only inswapper needs the emap projection matrix.
        let emap = match kind {
            SwapperKind::Inswapper => Some(Self::load_emap(model_path)?),
            SwapperKind::Hyperswap => None,
        };

        let ort_log_level = match get_verbose_level() {
            VerboseLevel::None => LogLevel::Fatal,
            VerboseLevel::Main => LogLevel::Fatal,
            VerboseLevel::Additional => LogLevel::Warning,
            VerboseLevel::All => LogLevel::Verbose,
        };

        #[cfg(feature = "cuda")]
        let session = Session::builder()
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .with_log_level(ort_log_level)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .with_execution_providers([CUDAExecutionProvider::default().build()])
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .with_optimization_level(GraphOptimizationLevel::Level3)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .commit_from_file(model_path)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?;

        #[cfg(not(feature = "cuda"))]
        let session = Session::builder()
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .with_log_level(ort_log_level)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .with_optimization_level(GraphOptimizationLevel::Level3)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .commit_from_file(model_path)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?;

        Ok(Self { session, kind, emap })
    }

    /// Which family this swapper belongs to.
    pub fn kind(&self) -> SwapperKind {
        self.kind
    }

    /// Native aligned-face size in pixels (128 or 256).
    pub fn model_size(&self) -> usize {
        self.kind.model_size()
    }

    /// Largest pixel-boost grid this family supports before exceeding 1024px.
    pub fn max_grid(&self) -> usize {
        (MAX_SWAP_SIZE / self.model_size()).max(1)
    }

    /// Load emap transformation matrix from binary file (inswapper only)
    ///
    /// The emap matrix projects face embeddings into the model's latent space.
    /// It must be extracted from the ONNX model file once and saved as binary.
    ///
    /// # Arguments
    /// * `model_path` - Path to the ONNX model (emap path is derived from this)
    ///
    /// # Returns
    /// 512x512 transformation matrix as Array2<f32>
    fn load_emap(model_path: &str) -> Result<Array2<f32>> {
        use std::fs::File;
        use std::io::Read;

        // Derive emap path from model path
        let emap_path = model_path.replace(".onnx", "_emap.bin");

        let mut file = File::open(&emap_path).map_err(|e| {
            FaceSwapError::ModelNotFound(format!(
                "Cannot open emap file {}: {}. Please extract emap using Python.",
                emap_path, e
            ))
        })?;

        let mut buffer = Vec::new();
        file.read_to_end(&mut buffer)
            .map_err(|e| FaceSwapError::ProcessingError(format!("Cannot read emap file: {}", e)))?;

        // Parse as f32 array
        let float_data: Vec<f32> = buffer
            .chunks_exact(4)
            .map(|chunk| f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
            .collect();

        // emap should be 512x512
        if float_data.len() != 512 * 512 {
            return Err(FaceSwapError::ProcessingError(format!(
                "Expected emap to be 512x512, got {} elements",
                float_data.len()
            )));
        }

        Array2::from_shape_vec((512, 512), float_data)
            .map_err(|e| FaceSwapError::ProcessingError(format!("Cannot create emap array: {}", e)))
    }

    /// Turn a source embedding into the exact input this family expects.
    ///
    /// * inswapper: `latent = embedding @ emap`, then L2-normalized
    /// * HyperSwap: the embedding itself, L2-normalized (it is already
    ///   normalized by the recogniser, but we do not rely on that)
    fn prepare_source_input(&self, source_embedding: &[f32]) -> Result<Array2<f32>> {
        if source_embedding.len() != 512 {
            return Err(FaceSwapError::InvalidInput(format!(
                "Expected 512-d source embedding, got {}",
                source_embedding.len()
            )));
        }

        let embedding = Array1::from_vec(source_embedding.to_vec());
        let embedding_2d = embedding.insert_axis(ndarray::Axis(0));

        let transformed = match &self.emap {
            Some(emap) => embedding_2d.dot(emap),
            None => embedding_2d,
        };

        let norm: f32 = transformed.iter().map(|x| x * x).sum::<f32>().sqrt();
        if !norm.is_finite() || norm <= f32::EPSILON {
            return Err(FaceSwapError::ProcessingError(
                "Source embedding has zero norm".to_string(),
            ));
        }

        Ok(transformed / norm)
    }

    /// Scale a [0, 1] aligned face into this family's input range.
    ///
    /// inswapper expects [0, 1]; HyperSwap expects [-1, 1].
    fn scale_target(&self, aligned: &Array4<f32>) -> Array4<f32> {
        match self.kind {
            SwapperKind::Inswapper => aligned.clone(),
            SwapperKind::Hyperswap => aligned.mapv(|v| v * 2.0 - 1.0),
        }
    }

    /// Map the model's output back into the [0, 1] range the paste-back expects.
    fn unscale_output(&self, out: Array4<f32>) -> Array4<f32> {
        match self.kind {
            SwapperKind::Inswapper => out,
            SwapperKind::Hyperswap => out.mapv(|v| (v * 0.5 + 0.5).clamp(0.0, 1.0)),
        }
    }

    /// Swap face in target image with source embedding
    ///
    /// # Arguments
    /// * `target_face_aligned` - Aligned target face as NCHW, normalized to [0, 1].
    ///   The side must equal [`Self::model_size`] (128 for inswapper, 256 for HyperSwap).
    /// * `source_embedding` - 512-dimensional L2-normalized embedding from source face
    ///
    /// # Returns
    /// Swapped face as NCHW, normalized to [0, 1]
    pub fn swap(
        &mut self,
        target_face_aligned: &Array4<f32>,
        source_embedding: &[f32],
    ) -> Result<Array4<f32>> {
        log_additional!(EVENT_SWAP_FACE, "Swapping face");

        let size = self.model_size();
        let shape = target_face_aligned.shape();
        if shape[0] != 1 || shape[1] != 3 || shape[2] != size || shape[3] != size {
            return Err(FaceSwapError::InvalidInput(format!(
                "Expected target face shape (1, 3, {}, {}), got ({}, {}, {}, {})",
                size, size, shape[0], shape[1], shape[2], shape[3]
            )));
        }

        let source_input = self.prepare_source_input(source_embedding)?;
        let target_input = self.scale_target(target_face_aligned);

        // Take the model's declared first output rather than assuming a name:
        // inswapper calls it "output", and other families are free to differ.
        let output_name = self
            .session
            .outputs()
            .first()
            .map(|output| output.name().to_string())
            .ok_or_else(|| FaceSwapError::Ort("swapper model declares no outputs".to_string()))?;

        let swapped = {
            let outputs = self
                .session
                .run(inputs![
                    "target" => TensorRef::from_array_view(target_input.view())?,
                    "source" => TensorRef::from_array_view(source_input.view())?
                ])
                .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?;

            let array_d = outputs[output_name.as_str()]
                .try_extract_array::<f32>()
                .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
                .into_owned();

            array_d
                .into_dimensionality::<ndarray::Ix4>()
                .map_err(|e| FaceSwapError::ProcessingError(format!("{}", e)))?
        };

        Ok(self.unscale_output(swapped))
    }

    /// Swap a larger aligned face using the "pixel boost" technique.
    ///
    /// The swapper runs at its native size (128 or 256). To work above that, the
    /// aligned face is split into `grid * grid` sub-images by taking every
    /// `grid`-th pixel (an interleaved pixel grid), each sub-image is swapped at
    /// native size, and the swapped sub-images are interleaved back into the
    /// full-size result. This matches the reference implementation used by the
    /// desktop tools.
    ///
    /// # Arguments
    /// * `aligned` - Aligned face as NCHW (1, 3, size*grid, size*grid), normalized to [0, 1]
    /// * `source_embedding` - 512-dimensional L2-normalized embedding from source face
    /// * `grid` - Number of tiles per axis. Values below 2 fall back to `swap`
    ///
    /// # Returns
    /// Swapped face as NCHW (1, 3, size*grid, size*grid), normalized to [0, 1]
    pub fn swap_boosted(
        &mut self,
        aligned: &Array4<f32>,
        source_embedding: &[f32],
        grid: usize,
    ) -> Result<Array4<f32>> {
        if grid < 2 {
            return self.swap(aligned, source_embedding);
        }

        let model_size = self.model_size();
        let shape = aligned.shape();
        let size = model_size * grid;
        if shape[0] != 1 || shape[1] != 3 || shape[2] != size || shape[3] != size {
            return Err(FaceSwapError::InvalidInput(format!(
                "Expected boosted target shape (1, 3, {}, {}), got ({}, {}, {}, {})",
                size, size, shape[0], shape[1], shape[2], shape[3]
            )));
        }

        log_additional!(
            EVENT_SWAP_FACE,
            "Swapping face with pixel boost",
            grid = grid,
            size = size
        );

        let mut out = Array4::<f32>::zeros((1, 3, size, size));

        for tile_y in 0..grid {
            for tile_x in 0..grid {
                // De-interleave this tile out of the aligned face.
                let mut sub = Array4::<f32>::zeros((1, 3, model_size, model_size));
                for c in 0..3 {
                    for y in 0..model_size {
                        for x in 0..model_size {
                            sub[[0, c, y, x]] =
                                aligned[[0, c, y * grid + tile_y, x * grid + tile_x]];
                        }
                    }
                }

                let swapped = self.swap(&sub, source_embedding)?;

                // Interleave the swapped tile back into the full-size result.
                for c in 0..3 {
                    for y in 0..model_size {
                        for x in 0..model_size {
                            out[[0, c, y * grid + tile_y, x * grid + tile_x]] =
                                swapped[[0, c, y, x]];
                        }
                    }
                }
            }
        }

        Ok(out)
    }
}
