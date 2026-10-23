//! Process-wide tuning the host sets before loading models.

use std::sync::atomic::{AtomicI32, Ordering};

/// Threads each inference session may use. 0 leaves ONNX Runtime's default.
static INTRA_THREADS: AtomicI32 = AtomicI32::new(0);

/// Set the per-session thread budget.
///
/// This matters once several clones run at once. ONNX Runtime defaults every
/// session to "use all cores", so N clones spawn N pools over the same cores and
/// spend their time contending. Splitting the budget keeps the total at roughly
/// the core count.
pub fn set_intra_threads(count: i32) {
    INTRA_THREADS.store(count.max(0), Ordering::Relaxed);
}

/// The budget, or 0 to leave the choice to ONNX Runtime.
pub fn intra_threads() -> usize {
    let count = INTRA_THREADS.load(Ordering::Relaxed);
    if count <= 0 {
        0
    } else {
        count as usize
    }
}
