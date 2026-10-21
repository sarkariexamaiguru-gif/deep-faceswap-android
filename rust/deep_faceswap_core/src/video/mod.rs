//! Video processing module
//!
//! The `extraction` and `encoding` submodules require FFmpeg (via `ffmpeg-next`)
//! and are only compiled when the `video` feature is enabled. The Android build
//! disables this feature because cross-compiling FFmpeg for every Android ABI is
//! impractical; image face-swapping does not need it.

pub mod analysis;

#[cfg(feature = "video")]
pub mod encoding;
#[cfg(feature = "video")]
pub mod extraction;

pub use analysis::*;
#[cfg(feature = "video")]
pub use encoding::encode_video;
#[cfg(feature = "video")]
pub use extraction::extract_frames;
