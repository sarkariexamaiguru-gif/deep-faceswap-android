//! Deep FaceSwap Core Library
//!
//! Basic face swap between two images (single source + single target)
//!
//! This library provides:
//! - Face detection (YOLOv8n from buffalo_l)
//! - Face recognition (ArcFace w600k_r50 from buffalo_l)
//! - Face swapping (inswapper_128 and HyperSwap 256)
//! - Face enhancement (GFPGAN)
//!
//! The `video` module and the video swap entry points are gated behind the
//! `video` feature (enabled by default). Android builds disable it to avoid the
//! FFmpeg dependency.

pub mod config;
pub mod types;

pub mod alignment;
pub mod clustering;
pub mod detection;
pub mod enhancer;
pub mod landmark;
pub mod mouth_mask;
pub mod multi_face;
pub mod recognition;
pub mod swap;
pub mod tools;
pub mod swapper;
pub mod utils;
pub mod video;

#[macro_use]
pub mod verbose;

pub use swap::{
    detect_faces_for_mapping, swap_animation, swap_faces, swap_faces_mapped, swap_faces_reporting,
    SwapSession,
    swap_single_pair, swap_single_pair_opts, swap_with_mappings, MappingFace, MappingFaces,
    SwapOptions, SwapStage, MAX_PIXEL_BOOST,
};
#[cfg(feature = "video")]
pub use swap::{swap_video, swap_video_frames_single, swap_video_frames_with_mappings};
pub use types::{FaceSwapError, Result};
