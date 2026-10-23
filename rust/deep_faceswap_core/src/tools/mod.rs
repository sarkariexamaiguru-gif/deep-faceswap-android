//! Tools: the model runner and the registry.
//!
//! A "tool" is a model plus a description of what it does. The face swapper was
//! built as a fixed pipeline with each model wired by name and shape; this is the
//! opposite - a table of tools, each naming its own file, hash and licence, so
//! adding one is an entry rather than an engineering project.
//!
//! The registry lives in the app, not on a server. A list fetched at runtime
//! would let whoever controls it choose what the app runs; a compiled table with
//! a hash per file means a swapped mirror is caught, not executed.

pub mod edit;
pub mod upscale;

use crate::types::{FaceSwapError, Result};
use ndarray::Array3;
use ort::session::Session;
use serde::Serialize;

/// One downloadable file a tool needs.
#[derive(Debug, Clone, Serialize)]
pub struct ToolModel {
    /// Filename on disk, and the last path segment of the url.
    pub file: &'static str,
    pub url: &'static str,
    /// Checked after download. A mismatch is refused, not run.
    pub sha256: &'static str,
    pub bytes: u64,
    pub license: &'static str,
}

/// A tool the app can offer.
#[derive(Debug, Clone, Serialize)]
pub struct ToolSpec {
    pub id: &'static str,
    pub name: &'static str,
    pub category: &'static str,
    /// One line, shown under the name.
    pub blurb: &'static str,
    pub model: ToolModel,
    /// How much bigger the result is, where that is a fixed number.
    pub scale: u32,
}

/// Every tool the engine can run.
///
/// Adding one means adding an entry here and an arm in `run`, nothing else.
pub const TOOLS: &[ToolSpec] = &[ToolSpec {
    id: "upscale",
    name: "Upscale",
    category: "Repair",
    blurb: "Enlarge an image 4x and sharpen it.",
    model: ToolModel {
        file: "realesr-general-x4v3.onnx",
        url: "https://huggingface.co/CoderViking/realesr-general-x4v3-onnx/resolve/main/realesr-general-x4v3.onnx",
        sha256: "1940a93ee08283a0a7286183186357b1688fe9fa8ede74604b424586aaddf112",
        bytes: 4_866_417,
        license: "BSD-3-Clause",
    },
    scale: 4,
}];

/// The registry as JSON, so the app does not keep a second copy of this list.
pub fn registry_json() -> String {
    serde_json::to_string(TOOLS).unwrap_or_else(|_| "[]".to_string())
}

/// Run the instant tools - the arithmetic ones that need no model.
pub fn apply_edits(
    image: &Array3<u8>,
    params: &edit::EditParams,
) -> Result<Array3<u8>> {
    edit::apply(image, params)
}

/// The largest input edge we will upscale.
///
/// Not an arbitrary limit: a 4x pass over a 4000px photo would produce a
/// 16000x12000 image, which is 768 MB before any model work at all. Capping the
/// input keeps the output inside a phone's budget, and the caller is told rather
/// than killed.
pub const MAX_INPUT_EDGE: usize = 1024;

/// A loaded tool, ready to run.
///
/// The host holds one of these rather than an ONNX session, so the JNI layer
/// never has to name a runtime type.
pub struct ToolSession {
    tool_id: String,
    session: Session,
}

impl ToolSession {
    /// Load the model a tool needs.
    pub fn load(tool_id: &str, model_path: &str) -> Result<Self> {
        let session = match tool_id {
            "upscale" => upscale::load(model_path)?,
            other => {
                return Err(FaceSwapError::InvalidInput(format!("unknown tool: {other}")))
            }
        };
        Ok(Self {
            tool_id: tool_id.to_string(),
            session,
        })
    }

    pub fn tool_id(&self) -> &str {
        &self.tool_id
    }

    /// Run it over an RGB image.
    pub fn run(&mut self, image: &Array3<u8>) -> Result<Array3<u8>> {
        match self.tool_id.as_str() {
            "upscale" => upscale::run(&mut self.session, image),
            other => Err(FaceSwapError::InvalidInput(format!("unknown tool: {other}"))),
        }
    }
}
