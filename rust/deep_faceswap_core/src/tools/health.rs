//! The health check.
//!
//! When a swap fails the app has one sentence to give and no way to say which
//! of six files was the problem. This loads each one and reports, so a failure
//! names a file and a reason instead of reporting a symptom.
//!
//! It is deliberately a LOAD test and not a full contract test. Loading catches
//! the overwhelming majority of real failures - a truncated download, a file
//! that is not ONNX at all, a model from another export pipeline, a missing
//! converter - and it does so in seconds without a source face or a target.

use crate::config;
use ort::session::builder::GraphOptimizationLevel;
use ort::session::Session;

/// Load a model and describe it.
///
/// On success the detail is the input and output signature, which is what
/// usually explains a failure that loading alone does not: a model whose inputs
/// are not `target` and `source` will load perfectly and then refuse to swap.
pub fn check(path: &str) -> std::result::Result<String, String> {
    let file = std::path::Path::new(path);
    if !file.exists() {
        return Err("file is not there".to_string());
    }
    let bytes = match std::fs::metadata(file) {
        Ok(meta) => meta.len(),
        Err(e) => return Err(format!("could not read it: {e}")),
    };
    let megabytes = bytes as f64 / 1_048_576.0;
    if bytes < 1024 {
        return Err(format!(
            "only {} bytes - a download that did not finish",
            bytes
        ));
    }

    let session = Session::builder()
        .map_err(|e| format!("could not start ONNX Runtime: {e}"))?
        .with_optimization_level(GraphOptimizationLevel::Level1)
        .map_err(|e| format!("could not configure the runtime: {e}"))?
        .with_intra_threads(config::intra_threads())
        .map_err(|e| format!("could not set the thread budget: {e}"))?
        .commit_from_file(file)
        .map_err(|e| {
            // The raw ORT message is long and mostly noise; the first line is
            // the part that names the actual problem.
            let text = format!("{e}");
            let first = text.lines().next().unwrap_or("").trim().to_string();
            // The size goes in the failure too. A model that should be 13 MB
            // and is 2 MB has a diagnosis, and without the number that is
            // invisible - which is exactly how this one hid.
            let size = format!("{:.1} MB", megabytes);
            if first.is_empty() {
                format!("it will not load as an ONNX model (the file is {size})")
            } else {
                format!("it will not load: {first} (the file is {size})")
            }
        })?;

    let inputs: Vec<String> = session
        .inputs()
        .iter()
        .map(|i| i.name().to_string())
        .collect();
    let outputs: Vec<String> = session
        .outputs()
        .iter()
        .map(|o| o.name().to_string())
        .collect();

    Ok(format!(
        "{:.1} MB · in [{}] · out [{}]",
        bytes as f64 / 1_048_576.0,
        inputs.join(", "),
        outputs.join(", ")
    ))
}

/// A convenience wrapper for the one case the health check cannot see by
/// loading alone: a swapper needs its converter beside it.
pub fn converter_present(swapper_path: &str) -> Option<String> {
    let family = std::path::Path::new(swapper_path)
        .file_name()
        .map(|n| n.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_default();
    for name in ["ghost", "simswap", "hififace"] {
        if family.contains(name) {
            let dir = std::path::Path::new(swapper_path)
                .parent()
                .map(|p| p.to_path_buf())
                .unwrap_or_else(|| std::path::PathBuf::from("."));
            let sidecar = dir.join(format!("arcface_converter_{name}.onnx"));
            if !sidecar.exists() {
                return Some(format!("needs {} beside it", sidecar.display()));
            }
        }
    }
    None
}
