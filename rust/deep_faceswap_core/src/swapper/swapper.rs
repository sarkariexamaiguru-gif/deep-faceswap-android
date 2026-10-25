//! Face swapping: inswapper_128, HyperSwap 256, Ghost 256 and UniFace 256
//!
//! All four families share the same ONNX session setup and the same
//! `source` / `target` input names, but differ in three ways:
//!
//! | family     | native | identity input                    | target range |
//! |------------|--------|-----------------------------------|--------------|
//! | inswapper  | 128    | ArcFace embedding × emap, L2-norm | [0, 1]       |
//! | HyperSwap  | 256    | L2-normalized ArcFace embedding   | [-1, 1]      |
//! | Ghost      | 256    | ArcFace embedding via converter   | [-1, 1]      |
//! | UniFace    | 256    | an aligned **source image**       | [-1, 1]      |
//!
//! Notes
//! - The emap is a 512x512 projection matrix extracted from the inswapper ONNX
//!   file; only inswapper uses it.
//! - Ghost needs a separate converter model that maps the ArcFace embedding into
//!   its latent space. By convention it is looked up beside the swapper as
//!   `arcface_converter_<family>.onnx`.
//! - UniFace takes no embedding at all: it is fed an aligned source face warped
//!   with its own template, so callers must supply the source face and image.

use crate::alignment::FaceTemplate;
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
use std::path::{Path, PathBuf};

#[cfg(feature = "cuda")]
use ort::execution_providers::CUDAExecutionProvider;

/// Native aligned-face size for inswapper.
const INSWAPPER_SIZE: usize = 128;
/// Native aligned-face size for the 256px families.
const LARGE_SIZE: usize = 256;
/// Largest aligned face we will ever hand a swapper (matches the 1024 pixel
/// boost ceiling the desktop tools use).
pub const MAX_SWAP_SIZE: usize = 1024;

/// Which swapper family a model file belongs to.
///
/// Detected from the file name, which is reliable for the files this app
/// downloads and keeps the JNI surface unchanged.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SwapperKind {
    /// InsightFace inswapper_128 — emap-projected embedding, 128px.
    Inswapper,
    /// FaceFusion HyperSwap — normalized embedding, 256px.
    Hyperswap,
    /// ai-forever Ghost — converter-mapped embedding, 256px.
    Ghost,
    /// UniFace — an aligned source image, 256px.
    Uniface,
    /// SimSwap — converter-mapped embedding, 256px, standard arcface framing.
    Simswap,
    /// HifiFace — converter-mapped embedding, 256px, standard arcface framing.
    Hififace,
    /// FaceDancer — an aligned source frame, 256px.
    ///
    /// The framing here is the one ASSUMPTION in this list. FaceDancer comes from
    /// a mirror that does not follow the FaceFusion export convention, so unlike
    /// the others its canonical crop was not stated anywhere this app could
    /// read. The standard arcface framing is the best-supported guess, and the
    /// quality report is what settles it: a wrong template shows up as identity
    /// similarity dropping, which is exactly the kind of failure that used to be
    /// invisible.
    Facedancer,
}

impl SwapperKind {
    /// Infer the family from a model path.
    pub fn from_path(path: &str) -> Self {
        let lower = path.to_ascii_lowercase();
        if lower.contains("hyperswap") {
            Self::Hyperswap
        } else if lower.contains("ghost") {
            Self::Ghost
        } else if lower.contains("uniface") {
            Self::Uniface
        } else if lower.contains("simswap") {
            Self::Simswap
        } else if lower.contains("hififace") {
            Self::Hififace
        } else if lower.contains("facedancer") {
            Self::Facedancer
        } else {
            Self::Inswapper
        }
    }

    /// Native aligned-face size for this family.
    pub fn model_size(self) -> usize {
        match self {
            Self::Inswapper => INSWAPPER_SIZE,
            _ => LARGE_SIZE,
        }
    }

    /// Canonical framing this family was trained on.
    pub fn target_template(self) -> FaceTemplate {
        match self {
            // The standard arcface framing, which is what the FaceFusion exports
            // of all three of these were built against.
            Self::Inswapper
            | Self::Hyperswap
            | Self::Simswap
            | Self::Hififace
            | Self::Facedancer => FaceTemplate::Arcface128,
            Self::Ghost => FaceTemplate::Arcface112V1,
            Self::Uniface => FaceTemplate::Ffhq512,
        }
    }

    /// Whether identity is supplied as an aligned image rather than an embedding.
    pub fn needs_source_frame(self) -> bool {
        matches!(self, Self::Uniface | Self::Facedancer)
    }

    /// Whether this family needs a separate embedding converter model.
    pub fn needs_converter(self) -> bool {
        // Each of these supplies identity in an embedding space of its own, so
        // the ArcFace vector has to be mapped into it first. The sidecar is
        // found by family name, which is why the file on disk has to be called
        // `arcface_converter_<family>.onnx` and not something shorter.
        matches!(self, Self::Ghost | Self::Simswap | Self::Hififace)
    }

    /// Short name used to locate a converter sidecar.
    fn family_name(self) -> &'static str {
        match self {
            Self::Inswapper => "inswapper",
            Self::Hyperswap => "hyperswap",
            Self::Ghost => "ghost",
            Self::Uniface => "uniface",
            Self::Simswap => "simswap",
            Self::Hififace => "hififace",
            Self::Facedancer => "facedancer",
        }
    }
}

/// Face swapper model
pub struct FaceSwapper {
    /// ONNX Runtime session for the face swapping model
    session: Session,
    /// Which family this model belongs to
    kind: SwapperKind,
    /// Emap transformation matrix (512x512); inswapper only
    emap: Option<Array2<f32>>,
    /// Embedding converter session; Ghost only
    converter: Option<Session>,
}

impl FaceSwapper {
    /// Load a face swapper, inferring the family from the file name.
    pub fn new(model_path: &str) -> Result<Self> {
        Self::new_with_kind(model_path, SwapperKind::from_path(model_path))
    }

    /// Load a face swapper of an explicitly chosen family.
    ///
    /// For Ghost, the converter model is looked up beside the swapper as
    /// `arcface_converter_ghost.onnx`.
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
            _ => None,
        };

        let session = Self::load_session(model_path)?;

        // Only Ghost needs an embedding converter.
        let converter = if kind.needs_converter() {
            let path = Self::converter_path(model_path, kind);
            if !path.exists() {
                return Err(FaceSwapError::ModelNotFound(format!(
                    "This swapper needs its embedding converter at {}",
                    path.display()
                )));
            }
            log_main!(
                EVENT_LOAD_MODEL,
                "Loading embedding converter",
                path = path.display().to_string()
            );
            Some(Self::load_session(&path.to_string_lossy())?)
        } else {
            None
        };

        Ok(Self {
            session,
            kind,
            emap,
            converter,
        })
    }

    /// Build an ONNX Runtime session with the project's standard settings.
    fn load_session(model_path: &str) -> Result<Session> {
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
            .with_optimization_level(GraphOptimizationLevel::Level1)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .with_intra_threads(crate::config::intra_threads())
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .commit_from_file(model_path)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?;

        #[cfg(not(feature = "cuda"))]
        let session = Session::builder()
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .with_log_level(ort_log_level)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .with_optimization_level(GraphOptimizationLevel::Level1)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .with_intra_threads(crate::config::intra_threads())
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .commit_from_file(model_path)
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?;

        Ok(session)
    }

    /// Where a family's converter sidecar is expected to live.
    fn converter_path(model_path: &str, kind: SwapperKind) -> PathBuf {
        let dir = Path::new(model_path)
            .parent()
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| PathBuf::from("."));
        dir.join(format!("arcface_converter_{}.onnx", kind.family_name()))
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
    fn load_emap(model_path: &str) -> Result<Array2<f32>> {
        use std::fs::File;
        use std::io::Read;

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

        let float_data: Vec<f32> = buffer
            .chunks_exact(4)
            .map(|chunk| f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
            .collect();

        if float_data.len() != 512 * 512 {
            return Err(FaceSwapError::ProcessingError(format!(
                "Expected emap to be 512x512, got {} elements",
                float_data.len()
            )));
        }

        Array2::from_shape_vec((512, 512), float_data)
            .map_err(|e| FaceSwapError::ProcessingError(format!("Cannot create emap array: {}", e)))
    }

    /// Run a model and return its first output as a flat `(1, n)` matrix.
    ///
    /// Taking the declared first output rather than assuming a name keeps this
    /// working across families whose output tensors are named differently.
    fn run_first_output(
        session: &mut Session,
        input_name: &str,
        input: &Array2<f32>,
    ) -> Result<Array2<f32>> {
        let output_name = session
            .outputs()
            .first()
            .map(|output| output.name().to_string())
            .ok_or_else(|| FaceSwapError::Ort("model declares no outputs".to_string()))?;

        let outputs = session
            .run(inputs![input_name => TensorRef::from_array_view(input.view())?])
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?;

        let array = outputs[output_name.as_str()]
            .try_extract_array::<f32>()
            .map_err(|e| FaceSwapError::Ort(format!("{}", e)))?
            .into_owned();

        let flat: Vec<f32> = array.iter().copied().collect();
        let n = flat.len();
        if n == 0 {
            return Err(FaceSwapError::ProcessingError(
                "converter returned an empty tensor".to_string(),
            ));
        }
        Array2::from_shape_vec((1, n), flat)
            .map_err(|e| FaceSwapError::ProcessingError(format!("{}", e)))
    }

    /// Turn a source embedding into the exact vector input this family expects.
    fn prepare_embedding_input(&mut self, source_embedding: &[f32]) -> Result<Array2<f32>> {
        if source_embedding.len() != 512 {
            return Err(FaceSwapError::InvalidInput(format!(
                "Expected 512-d source embedding, got {}",
                source_embedding.len()
            )));
        }

        // Ghost: map through the converter and use the result as-is — the
        // reference pipeline does not re-normalize this one.
        if let Some(converter) = self.converter.as_mut() {
            let input = Array2::from_shape_vec((1, 512), source_embedding.to_vec())
                .map_err(|e| FaceSwapError::ProcessingError(format!("{}", e)))?;
            return Self::run_first_output(converter, "input", &input);
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
    fn scale_target(&self, aligned: &Array4<f32>) -> Array4<f32> {
        match self.kind {
            SwapperKind::Inswapper => aligned.clone(),
            _ => aligned.mapv(|v| v * 2.0 - 1.0),
        }
    }

    /// Map the model's output back into the [0, 1] range the paste-back expects.
    fn unscale_output(&self, out: Array4<f32>) -> Array4<f32> {
        match self.kind {
            SwapperKind::Inswapper => out,
            _ => out.mapv(|v| (v * 0.5 + 0.5).clamp(0.0, 1.0)),
        }
    }

    /// One model pass on one aligned tile.
    /// The two input names this model wants, found by name where possible.
    ///
    /// `target` and `source` are preferred because that is the convention most
    /// exports follow. Anything else falls back to declaration order, which is
    /// what a model with opaque names like `input_1` / `input_2` needs.
    fn input_names(&self) -> Result<(String, String)> {
        let declared: Vec<String> = self
            .session
            .inputs()
            .iter()
            .map(|input| input.name().to_string())
            .collect();
        if declared.len() < 2 {
            return Err(FaceSwapError::InvalidInput(format!(
                "A swapper needs two inputs - a target and a source - and this model declares {}",
                declared.len()
            )));
        }

        let find = |wanted: &str| {
            declared
                .iter()
                .find(|name| name.to_ascii_lowercase().contains(wanted))
                .cloned()
        };

        let target = find("target").unwrap_or_else(|| declared[0].clone());
        let source = find("source")
            .filter(|name| *name != target)
            .unwrap_or_else(|| {
                declared
                    .iter()
                    .find(|name| **name != target)
                    .cloned()
                    .unwrap_or_else(|| declared[1].clone())
            });
        Ok((target, source))
    }

    fn run_tile(
        &mut self,
        target: &Array4<f32>,
        source_embedding: &[f32],
        source_frame: Option<&Array4<f32>>,
    ) -> Result<Array4<f32>> {
        let size = self.model_size();
        let shape = target.shape();
        if shape[0] != 1 || shape[1] != 3 || shape[2] != size || shape[3] != size {
            return Err(FaceSwapError::InvalidInput(format!(
                "Expected target face shape (1, 3, {}, {}), got ({}, {}, {}, {})",
                size, size, shape[0], shape[1], shape[2], shape[3]
            )));
        }

        let target_input = self.scale_target(target);

        // UniFace is fed an aligned source image; every other family an embedding.
        let source_matrix = match source_frame {
            Some(_) => None,
            None => Some(self.prepare_embedding_input(source_embedding)?),
        };

        let output_name = self
            .session
            .outputs()
            .first()
            .map(|output| output.name().to_string())
            .ok_or_else(|| FaceSwapError::Ort("swapper model declares no outputs".to_string()))?;

        // The input names are DISCOVERED, not assumed.
        //
        // Every model this app shipped until now happened to use `target` and
        // `source`, which is the convention FaceFusion's exports follow - so a
        // hardcoded pair worked for six families and looked like a contract. It
        // is not one. A model from any other export pipeline names its inputs
        // whatever it likes, and the failure mode is an error at load rather
        // than anything subtle, so looking them up costs nothing and unlocks
        // models that were previously unwireable.
        let (target_name, source_name) = self.input_names()?;

        // The outputs hold a borrow of the session, so take what we need and let
        // them drop before touching self again.
        let swapped = {
            let outputs = match source_frame {
                Some(frame) => self.session.run(inputs![
                    target_name.as_str() => TensorRef::from_array_view(target_input.view())?,
                    source_name.as_str() => TensorRef::from_array_view(frame.view())?
                ]),
                None => {
                    let source = source_matrix.as_ref().ok_or_else(|| {
                        FaceSwapError::ProcessingError("missing source input".to_string())
                    })?;
                    self.session.run(inputs![
                        target_name.as_str() => TensorRef::from_array_view(target_input.view())?,
                        source_name.as_str() => TensorRef::from_array_view(source.view())?
                    ])
                }
            }
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

    /// Swap face in target image with source embedding
    ///
    /// # Arguments
    /// * `target_face_aligned` - Aligned target face as NCHW, normalized to [0, 1].
    ///   The side must equal [`Self::model_size`].
    /// * `source_embedding` - 512-dimensional L2-normalized embedding from source face
    pub fn swap(
        &mut self,
        target_face_aligned: &Array4<f32>,
        source_embedding: &[f32],
    ) -> Result<Array4<f32>> {
        log_additional!(EVENT_SWAP_FACE, "Swapping face");
        self.run_tile(target_face_aligned, source_embedding, None)
    }

    /// Swap using an aligned source image instead of an embedding (UniFace).
    pub fn swap_with_source_frame(
        &mut self,
        target_face_aligned: &Array4<f32>,
        source_frame: &Array4<f32>,
    ) -> Result<Array4<f32>> {
        log_additional!(EVENT_SWAP_FACE, "Swapping face from source frame");
        self.run_tile(target_face_aligned, &[], Some(source_frame))
    }

    /// Swap a larger aligned face using the "pixel boost" technique.
    ///
    /// The swapper runs at its native size (128 or 256). To work above that, the
    /// aligned face is split into `grid * grid` sub-images by taking every
    /// `grid`-th pixel (an interleaved pixel grid), each sub-image is swapped at
    /// native size, and the swapped sub-images are interleaved back into the
    /// full-size result. This matches the reference implementation used by the
    /// desktop tools.
    pub fn swap_boosted(
        &mut self,
        aligned: &Array4<f32>,
        source_embedding: &[f32],
        grid: usize,
    ) -> Result<Array4<f32>> {
        self.swap_boosted_full(aligned, source_embedding, None, grid)
    }

    /// Pixel-boosted swap that can also take an aligned source image.
    pub fn swap_boosted_full(
        &mut self,
        aligned: &Array4<f32>,
        source_embedding: &[f32],
        source_frame: Option<&Array4<f32>>,
        grid: usize,
    ) -> Result<Array4<f32>> {
        if grid < 2 {
            return self.run_tile(aligned, source_embedding, source_frame);
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

                let swapped = self.run_tile(&sub, source_embedding, source_frame)?;

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
