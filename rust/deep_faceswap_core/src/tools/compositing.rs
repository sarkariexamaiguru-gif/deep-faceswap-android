//! The compositing pass: whose skin is it.
//!
//! Every swap so far carries the SOURCE's skin texture onto the target's face.
//! Pores, stubble, fine lines - all from the wrong person, and one of the
//! clearest tells in a swapped image.
//!
//! Frequency separation is the technique retouchers actually use. Split a face
//! into low frequencies (colour, light, form) and high frequencies (texture),
//! take the identity from the source's low band and the texture from the
//! TARGET's high band, and put them back together.
//!
//! One property makes this safe to apply over a whole face box rather than
//! precisely where the swap landed: where nothing was swapped, the two inputs
//! are identical, so the result is `blur(x) + (x - blur(x)) = x` exactly. Pixels
//! the swap did not touch pass through unchanged.

use ndarray::Array3;

/// Radius of the low-frequency blur, as a fraction of the shorter side.
///
/// Too wide and the skin goes plastic; too narrow and the source's texture
/// survives. This separates "the form of a face" from "the grain of its skin"
/// at face-crop resolutions.
const BLUR_FRACTION: f32 = 0.02;

/// How much of the target's texture to keep. 1.0 is all of it, 0.0 is none.
pub const DEFAULT_TEXTURE: f32 = 0.85;

/// Recombine: the swapped face's form, the target's texture.
///
/// `swapped` and `original` must be the same region of the same size - the box
/// taken before the swap and after it.
pub fn separate_frequencies(
    swapped: &Array3<u8>,
    original: &Array3<u8>,
    texture: f32,
) -> Array3<u8> {
    let (h, w, _) = swapped.dim();
    if original.dim() != (h, w, 3) || h < 3 || w < 3 {
        return swapped.clone();
    }

    let radius = ((h.min(w) as f32 * BLUR_FRACTION).round() as usize).clamp(1, 24);
    let amount = texture.clamp(0.0, 1.0);

    // The low band of the swapped face: colour, light, the form of the person.
    let low_swapped = blur_rgb(swapped, radius);
    // The low band of the ORIGINAL target, so its detail can be isolated.
    let low_original = blur_rgb(original, radius);

    let mut out = Array3::<u8>::zeros((h, w, 3));
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                let low = low_swapped[[y, x, c]] as f32;
                let original_value = original[[y, x, c]] as f32;
                let low_orig = low_original[[y, x, c]] as f32;
                // The target's own detail, relative to its own low band.
                let detail = original_value - low_orig;
                out[[y, x, c]] = (low + detail * amount).clamp(0.0, 255.0) as u8;
            }
        }
    }
    out
}

/// Separable box blur, three passes, which approximates a Gaussian closely
/// enough for this and costs a fraction of one.
pub fn blur_rgb(image: &Array3<u8>, radius: usize) -> Array3<u8> {
    if radius == 0 {
        return image.clone();
    }
    let mut current = image.clone();
    for _ in 0..3 {
        current = box_pass_horizontal(&current, radius);
        current = box_pass_vertical(&current, radius);
    }
    current
}

fn box_pass_horizontal(image: &Array3<u8>, radius: usize) -> Array3<u8> {
    let (h, w, c) = image.dim();
    let mut out = Array3::<u8>::zeros((h, w, c));
    let span = (radius * 2 + 1) as u32;

    for y in 0..h {
        // Running sum per channel, so each output pixel costs one add and one
        // subtract rather than a fresh window sum.
        let mut sums = [0u32; 4];
        for x in 0..w + radius {
            let add = x + radius;
            if add < w {
                for ch in 0..c.min(4) {
                    sums[ch] += image[[y, add, ch]] as u32;
                }
            }
            let drop = x as isize - radius as isize - 1;
            if drop >= 0 {
                for ch in 0..c.min(4) {
                    sums[ch] -= image[[y, drop as usize, ch]] as u32;
                }
            }
            if x < w {
                for ch in 0..c.min(4) {
                    out[[y, x, ch]] = (sums[ch] / span) as u8;
                }
            }
        }
    }
    out
}

fn box_pass_vertical(image: &Array3<u8>, radius: usize) -> Array3<u8> {
    let (h, w, c) = image.dim();
    let mut out = Array3::<u8>::zeros((h, w, c));
    let span = (radius * 2 + 1) as u32;

    for x in 0..w {
        let mut sums = [0u32; 4];
        for y in 0..h + radius {
            let add = y + radius;
            if add < h {
                for ch in 0..c.min(4) {
                    sums[ch] += image[[add, x, ch]] as u32;
                }
            }
            let drop = y as isize - radius as isize - 1;
            if drop >= 0 {
                for ch in 0..c.min(4) {
                    sums[ch] -= image[[drop as usize, x, ch]] as u32;
                }
            }
            if y < h {
                for ch in 0..c.min(4) {
                    out[[y, x, ch]] = (sums[ch] / span) as u8;
                }
            }
        }
    }
    out
}
