//! The quality report.
//!
//! Every quality claim in this project so far has been an estimate. This is the
//! thing that makes them measurable: three numbers per swap, each of which a
//! change either moves or does not.
//!
//! * **ID similarity** - cosine between the source face's embedding and the
//!   swapped face's own. Answers "does it actually look like the person".
//! * **Sharpness** - Laplacian variance of the face region, before and after.
//!   Answers "did it get softer or crisper".
//! * **Occlusion coverage** - what fraction of the face the occluder protected.
//!   Answers "how much of this face was behind something".
//!
//! All three are cheap: no extra model is loaded, and the recogniser and the
//! source images are already in hand at the point they are computed.
//!
//! A process-wide slot, for the same reason the occluder uses one - the swap
//! signature and its call layers would otherwise need threading for a value that
//! only ever describes the most recent frame.

use crate::types::BBox;
use ndarray::Array3;
use std::sync::Mutex;

/// What the last swap scored. Every field is optional because a metric that
/// could not be computed is better reported as absent than as a misleading zero.
#[derive(Clone, Default, serde::Serialize)]
pub struct QualityReport {
    pub id_similarity: Option<f64>,
    pub sharpness_before: Option<f64>,
    pub sharpness_after: Option<f64>,
    pub occlusion_coverage: Option<f64>,
    pub faces_swapped: usize,
}

static REPORT: Mutex<QualityReport> = Mutex::new(QualityReport {
    id_similarity: None,
    sharpness_before: None,
    sharpness_after: None,
    occlusion_coverage: None,
    faces_swapped: 0,
});

fn lock() -> std::sync::MutexGuard<'static, QualityReport> {
    match REPORT.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

/// Clear the report at the start of a job, so a stale number from a previous
/// run can never be shown.
pub fn reset() {
    let mut report = lock();
    *report = QualityReport::default();
}

pub fn record_faces(count: usize) {
    lock().faces_swapped = count;
}

pub fn record_sharpness(before: f64, after: f64) {
    let mut report = lock();
    report.sharpness_before = Some(before);
    report.sharpness_after = Some(after);
}

pub fn record_id(value: f64) {
    lock().id_similarity = Some(value);
}

pub fn record_occlusion(coverage: f64) {
    lock().occlusion_coverage = Some(coverage);
}

/// The report as JSON, for the bridge.
pub fn report_json() -> String {
    let report = lock();
    serde_json::json!({
        "idSimilarity": report.id_similarity,
        "sharpnessBefore": report.sharpness_before,
        "sharpnessAfter": report.sharpness_after,
        "occlusionCoverage": report.occlusion_coverage,
        "facesSwapped": report.faces_swapped,
    })
    .to_string()
}

/// Laplacian variance over the face region: the standard no-reference sharpness
/// measure, and cheap enough to run twice per frame.
///
/// The box is inset slightly, because the edge of a detected face box lands on
/// background as often as on skin, and background edges are very high frequency.
pub fn face_sharpness(image: &Array3<u8>, bbox: &BBox) -> f64 {
    let (h, w, _) = image.dim();
    let bw = bbox.x2 - bbox.x1;
    let bh = bbox.y2 - bbox.y1;
    let inset_x = bw * 0.15;
    let inset_y = bh * 0.15;

    let x0 = (bbox.x1 + inset_x).floor().max(0.0) as usize;
    let y0 = (bbox.y1 + inset_y).floor().max(0.0) as usize;
    let x1 = ((bbox.x2 - inset_x).ceil() as usize).min(w);
    let y1 = ((bbox.y2 - inset_y).ceil() as usize).min(h);
    if x1 <= x0 + 2 || y1 <= y0 + 2 {
        return 0.0;
    }

    let mut responses = Vec::with_capacity((y1 - y0) * (x1 - x0));
    for y in (y0 + 1)..(y1 - 1) {
        for x in (x0 + 1)..(x1 - 1) {
            let mut acc = 0.0f64;
            for c in 0..3 {
                let centre = image[[y, x, c]] as f64;
                let up = image[[y - 1, x, c]] as f64;
                let down = image[[y + 1, x, c]] as f64;
                let left = image[[y, x - 1, c]] as f64;
                let right = image[[y, x + 1, c]] as f64;
                acc += up + down + left + right - 4.0 * centre;
            }
            responses.push(acc / 3.0);
        }
    }

    variance(&responses)
}

/// Variance of a set of numbers. Zero for an empty or single-element set.
pub fn variance(values: &[f64]) -> f64 {
    let n = values.len() as f64;
    if n < 2.0 {
        return 0.0;
    }
    let mean = values.iter().sum::<f64>() / n;
    values.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / n
}

/// Cosine similarity, for comparing two face embeddings.
///
/// Both sides are L2-normalised by the recogniser already, so this is close to a
/// dot product - but normalising here means a caller cannot get it wrong.
pub fn cosine(a: &[f32], b: &[f32]) -> f64 {
    if a.len() != b.len() || a.is_empty() {
        return 0.0;
    }
    let mut dot = 0.0f64;
    let mut na = 0.0f64;
    let mut nb = 0.0f64;
    for i in 0..a.len() {
        let x = a[i] as f64;
        let y = b[i] as f64;
        dot += x * y;
        na += x * x;
        nb += y * y;
    }
    if na <= 0.0 || nb <= 0.0 {
        return 0.0;
    }
    dot / (na.sqrt() * nb.sqrt())
}
