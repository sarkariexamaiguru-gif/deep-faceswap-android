//! The instant tools: everything that is arithmetic.
//!
//! Crop, rotate, flip, resize, light, colour, sharpen, mono, auto-colour. No
//! model, no download, no failure mode beyond a bad parameter — which is exactly
//! why they are worth building first. They are also the majority of what an
//! image editor actually does, and they run in milliseconds.

use crate::types::{FaceSwapError, Result};
use ndarray::{s, Array3};
use serde::Deserialize;

/// Every adjustment, in one structure.
///
/// The app presents these as separate tools — Crop, Light, Colour, Sharpen —
/// but the engine treats them as one pass, because that is what they are: a
/// chain of pixel operations with no model in sight. Splitting them here would
/// mean re-encoding the image between each step for no reason.
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct EditParams {
    /// Crop rectangle in source pixels. Zero width or height means no crop.
    pub crop_x: u32,
    pub crop_y: u32,
    pub crop_w: u32,
    pub crop_h: u32,
    /// 0, 90, 180 or 270. Anything else is rounded to the nearest quarter.
    pub rotate: u32,
    pub flip_h: bool,
    pub flip_v: bool,
    /// -1 (black) .. 0 (unchanged) .. 1 (white)
    pub brightness: f32,
    /// -1 (flat) .. 0 .. 1 (hard)
    pub contrast: f32,
    /// -1 (grey) .. 0 .. 1 (vivid)
    pub saturation: f32,
    /// -1 (cooler) .. 0 .. 1 (warmer)
    pub temperature: f32,
    /// 0 .. 1, unsharp mask strength
    pub sharpen: f32,
    pub mono: bool,
    /// Stretch each channel to its own range first.
    pub auto_colour: bool,
    /// A look: 0 is none, the rest are the one-tap filters. Applied after the
    /// sliders and before mono, so a look never fights the controls.
    pub look: u32,
    /// Exact output size. Zero means leave it.
    pub resize_w: u32,
    pub resize_h: u32,
}

impl Default for EditParams {
    fn default() -> Self {
        Self {
            crop_x: 0,
            crop_y: 0,
            crop_w: 0,
            crop_h: 0,
            rotate: 0,
            flip_h: false,
            flip_v: false,
            brightness: 0.0,
            contrast: 0.0,
            saturation: 0.0,
            temperature: 0.0,
            sharpen: 0.0,
            mono: false,
            auto_colour: false,
            look: 0,
            resize_w: 0,
            resize_h: 0,
        }
    }
}

/// Run the whole chain over an RGB image.
pub fn apply(image: &Array3<u8>, params: &EditParams) -> Result<Array3<u8>> {
    let (h, w, c) = image.dim();
    if h == 0 || w == 0 || c != 3 {
        return Err(FaceSwapError::InvalidInput(
            "the image must be non-empty RGB".to_string(),
        ));
    }

    let mut out = image.clone();

    out = crop(&out, params);
    out = rotate(&out, params.rotate);
    out = flip(&out, params.flip_h, params.flip_v);

    if params.auto_colour {
        out = auto_colour(&out);
    }
    if params.brightness != 0.0
        || params.contrast != 0.0
        || params.saturation != 0.0
        || params.temperature != 0.0
    {
        out = grade(&out, params);
    }
    if params.look > 0 {
        out = look(&out, params.look);
    }
    if params.sharpen > 0.0 {
        out = unsharp(&out, params.sharpen.clamp(0.0, 1.0));
    }
    if params.mono {
        out = to_mono(&out);
    }
    if params.resize_w > 0 && params.resize_h > 0 {
        out = resize(&out, params.resize_w as usize, params.resize_h as usize);
    }

    Ok(out)
}

/// Crop, clamped to the image so an out-of-range rectangle cannot panic.
fn crop(image: &Array3<u8>, params: &EditParams) -> Array3<u8> {
    let (h, w, _) = image.dim();
    if params.crop_w == 0 || params.crop_h == 0 {
        return image.clone();
    }
    let x0 = (params.crop_x as usize).min(w.saturating_sub(1));
    let y0 = (params.crop_y as usize).min(h.saturating_sub(1));
    let x1 = (x0 + params.crop_w as usize).min(w);
    let y1 = (y0 + params.crop_h as usize).min(h);
    image.slice(s![y0..y1, x0..x1, ..]).to_owned()
}

/// Quarter turns, clockwise.
fn rotate(image: &Array3<u8>, quarter: u32) -> Array3<u8> {
    let (h, w, _) = image.dim();
    match (quarter / 90) % 4 {
        0 => image.clone(),
        1 => {
            let mut out = Array3::<u8>::zeros((w, h, 3));
            for y in 0..h {
                for x in 0..w {
                    for c in 0..3 {
                        out[[x, h - 1 - y, c]] = image[[y, x, c]];
                    }
                }
            }
            out
        }
        2 => {
            let mut out = Array3::<u8>::zeros((h, w, 3));
            for y in 0..h {
                for x in 0..w {
                    for c in 0..3 {
                        out[[h - 1 - y, w - 1 - x, c]] = image[[y, x, c]];
                    }
                }
            }
            out
        }
        _ => {
            let mut out = Array3::<u8>::zeros((w, h, 3));
            for y in 0..h {
                for x in 0..w {
                    for c in 0..3 {
                        out[[w - 1 - x, y, c]] = image[[y, x, c]];
                    }
                }
            }
            out
        }
    }
}

fn flip(image: &Array3<u8>, horizontal: bool, vertical: bool) -> Array3<u8> {
    if !horizontal && !vertical {
        return image.clone();
    }
    let (h, w, _) = image.dim();
    let mut out = Array3::<u8>::zeros((h, w, 3));
    for y in 0..h {
        for x in 0..w {
            let sy = if vertical { h - 1 - y } else { y };
            let sx = if horizontal { w - 1 - x } else { x };
            for c in 0..3 {
                out[[y, x, c]] = image[[sy, sx, c]];
            }
        }
    }
    out
}

/// Stretch each channel between its own low and high percentiles.
///
/// Percentiles rather than min and max: one blown highlight should not decide
/// the whole range, which is the usual reason auto-colour looks wrong.
fn auto_colour(image: &Array3<u8>) -> Array3<u8> {
    let (h, w, _) = image.dim();
    let total = h * w;
    let mut out = image.clone();

    for c in 0..3 {
        let mut hist = [0u32; 256];
        for y in 0..h {
            for x in 0..w {
                hist[image[[y, x, c]] as usize] += 1;
            }
        }
        let low_cut = (total / 200).max(1) as u32; // 0.5%
        let high_cut = low_cut;

        let mut acc = 0u32;
        let mut lo = 0usize;
        for (v, count) in hist.iter().enumerate() {
            acc += count;
            if acc >= low_cut {
                lo = v;
                break;
            }
        }
        acc = 0;
        let mut hi = 255usize;
        for (v, count) in hist.iter().enumerate().rev() {
            acc += count;
            if acc >= high_cut {
                hi = v;
                break;
            }
        }
        if hi <= lo {
            continue;
        }
        let scale = 255.0 / (hi - lo) as f32;
        for y in 0..h {
            for x in 0..w {
                let v = image[[y, x, c]] as f32;
                let stretched = ((v - lo as f32) * scale).clamp(0.0, 255.0);
                out[[y, x, c]] = stretched as u8;
            }
        }
    }
    out
}

/// Brightness, contrast, saturation and temperature in one pass.
fn grade(image: &Array3<u8>, p: &EditParams) -> Array3<u8> {
    let (h, w, _) = image.dim();
    let mut out = Array3::<u8>::zeros((h, w, 3));

    let contrast = 1.0 + p.contrast.clamp(-1.0, 1.0);
    let saturation = 1.0 + p.saturation.clamp(-1.0, 1.0);
    let bright = p.brightness.clamp(-1.0, 1.0) * 255.0;
    let warm = p.temperature.clamp(-1.0, 1.0) * 40.0;

    for y in 0..h {
        for x in 0..w {
            let r = image[[y, x, 0]] as f32 + bright + warm;
            let g = image[[y, x, 1]] as f32 + bright;
            let b = image[[y, x, 2]] as f32 + bright - warm;

            // Contrast pivots on mid grey.
            let r = (r - 128.0) * contrast + 128.0;
            let g = (g - 128.0) * contrast + 128.0;
            let b = (b - 128.0) * contrast + 128.0;

            // Saturation pulls towards the luminance of the pixel.
            let luma = 0.299 * r + 0.587 * g + 0.114 * b;
            let r = luma + (r - luma) * saturation;
            let g = luma + (g - luma) * saturation;
            let b = luma + (b - luma) * saturation;

            out[[y, x, 0]] = r.clamp(0.0, 255.0) as u8;
            out[[y, x, 1]] = g.clamp(0.0, 255.0) as u8;
            out[[y, x, 2]] = b.clamp(0.0, 255.0) as u8;
        }
    }
    out
}

/// The names, in index order. Index 0 is no look.
pub const LOOK_NAMES: &[&str] = &[
    "Original", "Vivid", "Warm", "Cool", "Fade", "Vintage", "Sepia", "Dramatic",
    "Film", "Punch", "Matte", "Noir", "Golden", "Forest",
];

/// One-tap looks.
///
/// All arithmetic - no model, no download, milliseconds. This is the part of an
/// editor that people actually reach for, and it costs nothing to run.
fn look(image: &Array3<u8>, index: u32) -> Array3<u8> {
    let (h, w, _) = image.dim();
    let mut out = Array3::<u8>::zeros((h, w, 3));

    for y in 0..h {
        for x in 0..w {
            let mut r = image[[y, x, 0]] as f32;
            let mut g = image[[y, x, 1]] as f32;
            let mut b = image[[y, x, 2]] as f32;

            match index {
                // Vivid: more colour, a little more snap.
                1 => {
                    (r, g, b) = saturate(r, g, b, 1.35);
                    (r, g, b) = contrast(r, g, b, 1.12);
                }
                // Warm: push red, pull blue.
                2 => {
                    r += 16.0;
                    g += 5.0;
                    b -= 14.0;
                    (r, g, b) = saturate(r, g, b, 1.08);
                }
                // Cool: the other way.
                3 => {
                    r -= 12.0;
                    g += 2.0;
                    b += 18.0;
                }
                // Fade: lift the blacks and take the colour down.
                4 => {
                    (r, g, b) = lift(r, g, b, 30.0);
                    (r, g, b) = contrast(r, g, b, 0.88);
                    (r, g, b) = saturate(r, g, b, 0.82);
                }
                // Vintage: fade, warmth, and a touch of yellow.
                5 => {
                    (r, g, b) = lift(r, g, b, 26.0);
                    (r, g, b) = contrast(r, g, b, 0.9);
                    (r, g, b) = saturate(r, g, b, 0.75);
                    r += 12.0;
                    g += 6.0;
                    b -= 10.0;
                }
                // Sepia: the classic matrix.
                6 => {
                    let (sr, sg, sb) = (
                        0.393 * r + 0.769 * g + 0.189 * b,
                        0.349 * r + 0.686 * g + 0.168 * b,
                        0.272 * r + 0.534 * g + 0.131 * b,
                    );
                    r = sr;
                    g = sg;
                    b = sb;
                }
                // Dramatic: heavy contrast, colour pulled back.
                7 => {
                    (r, g, b) = contrast(r, g, b, 1.45);
                    (r, g, b) = saturate(r, g, b, 0.7);
                }
                // Film: lifted blacks, slightly green in the shadows.
                8 => {
                    (r, g, b) = lift(r, g, b, 22.0);
                    (r, g, b) = contrast(r, g, b, 1.05);
                    g += 5.0;
                    b += 3.0;
                }
                // Punch: the loud one.
                9 => {
                    (r, g, b) = contrast(r, g, b, 1.3);
                    (r, g, b) = saturate(r, g, b, 1.5);
                }
                // Matte: flat and muted.
                10 => {
                    (r, g, b) = lift(r, g, b, 38.0);
                    (r, g, b) = contrast(r, g, b, 0.8);
                    (r, g, b) = saturate(r, g, b, 0.85);
                }
                // Noir: black and white, high contrast.
                11 => {
                    (r, g, b) = contrast(r, g, b, 1.4);
                    let luma = luma_of(r, g, b);
                    r = luma;
                    g = luma;
                    b = luma;
                }
                // Golden: warm highlights.
                12 => {
                    r += 22.0;
                    g += 12.0;
                    b -= 16.0;
                    (r, g, b) = contrast(r, g, b, 1.06);
                }
                // Forest: cool and green.
                13 => {
                    g += 14.0;
                    b += 6.0;
                    r -= 14.0;
                    (r, g, b) = saturate(r, g, b, 0.92);
                }
                _ => {}
            }

            out[[y, x, 0]] = r.clamp(0.0, 255.0) as u8;
            out[[y, x, 1]] = g.clamp(0.0, 255.0) as u8;
            out[[y, x, 2]] = b.clamp(0.0, 255.0) as u8;
        }
    }
    out
}

fn luma_of(r: f32, g: f32, b: f32) -> f32 {
    0.299 * r + 0.587 * g + 0.114 * b
}

/// Pull towards or away from grey.
fn saturate(r: f32, g: f32, b: f32, amount: f32) -> (f32, f32, f32) {
    let luma = luma_of(r, g, b);
    (
        luma + (r - luma) * amount,
        luma + (g - luma) * amount,
        luma + (b - luma) * amount,
    )
}

/// Pivot on mid grey.
fn contrast(r: f32, g: f32, b: f32, amount: f32) -> (f32, f32, f32) {
    (
        (r - 128.0) * amount + 128.0,
        (g - 128.0) * amount + 128.0,
        (b - 128.0) * amount + 128.0,
    )
}

/// Raise the floor, which is what makes a fade look like a fade.
fn lift(r: f32, g: f32, b: f32, amount: f32) -> (f32, f32, f32) {
    let f = amount / 255.0;
    (
        r * (1.0 - f) + amount,
        g * (1.0 - f) + amount,
        b * (1.0 - f) + amount,
    )
}

/// Unsharp mask: the original plus a multiple of the difference from a blur.
fn unsharp(image: &Array3<u8>, amount: f32) -> Array3<u8> {
    let (h, w, _) = image.dim();
    if h < 3 || w < 3 {
        return image.clone();
    }
    let mut out = Array3::<u8>::zeros((h, w, 3));

    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                // A 3x3 box blur, edges clamped.
                let mut sum = 0.0f32;
                let mut count = 0.0f32;
                for dy in -1i32..=1 {
                    for dx in -1i32..=1 {
                        let sy = (y as i32 + dy).clamp(0, h as i32 - 1) as usize;
                        let sx = (x as i32 + dx).clamp(0, w as i32 - 1) as usize;
                        sum += image[[sy, sx, c]] as f32;
                        count += 1.0;
                    }
                }
                let blur = sum / count;
                let original = image[[y, x, c]] as f32;
                let sharpened = original + (original - blur) * amount * 1.6;
                out[[y, x, c]] = sharpened.clamp(0.0, 255.0) as u8;
            }
        }
    }
    out
}

fn to_mono(image: &Array3<u8>) -> Array3<u8> {
    let (h, w, _) = image.dim();
    let mut out = Array3::<u8>::zeros((h, w, 3));
    for y in 0..h {
        for x in 0..w {
            let luma = (0.299 * image[[y, x, 0]] as f32
                + 0.587 * image[[y, x, 1]] as f32
                + 0.114 * image[[y, x, 2]] as f32)
                .clamp(0.0, 255.0) as u8;
            out[[y, x, 0]] = luma;
            out[[y, x, 1]] = luma;
            out[[y, x, 2]] = luma;
        }
    }
    out
}

/// Bilinear resize. Not the model-based upscaler — this is the plain resize.
fn resize(image: &Array3<u8>, new_w: usize, new_h: usize) -> Array3<u8> {
    let (h, w, _) = image.dim();
    if new_w == 0 || new_h == 0 || (new_w == w && new_h == h) {
        return image.clone();
    }
    let mut out = Array3::<u8>::zeros((new_h, new_w, 3));
    let x_ratio = if new_w > 1 {
        (w - 1) as f32 / (new_w - 1) as f32
    } else {
        0.0
    };
    let y_ratio = if new_h > 1 {
        (h - 1) as f32 / (new_h - 1) as f32
    } else {
        0.0
    };

    for y in 0..new_h {
        let sy = y as f32 * y_ratio;
        let y0 = sy.floor() as usize;
        let y1 = (y0 + 1).min(h - 1);
        let fy = sy - y0 as f32;

        for x in 0..new_w {
            let sx = x as f32 * x_ratio;
            let x0 = sx.floor() as usize;
            let x1 = (x0 + 1).min(w - 1);
            let fx = sx - x0 as f32;

            for c in 0..3 {
                let top = image[[y0, x0, c]] as f32 * (1.0 - fx) + image[[y0, x1, c]] as f32 * fx;
                let bottom = image[[y1, x0, c]] as f32 * (1.0 - fx) + image[[y1, x1, c]] as f32 * fx;
                out[[y, x, c]] = (top * (1.0 - fy) + bottom * fy).clamp(0.0, 255.0) as u8;
            }
        }
    }
    out
}
