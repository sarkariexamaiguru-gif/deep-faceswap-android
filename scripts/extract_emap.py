#!/usr/bin/env python3
"""Extract the inswapper ``emap`` matrix from an ONNX model into a raw binary.

The Rust swapper expects a sidecar file ``inswapper_128_emap.bin`` next to
``inswapper_128.onnx``: a 512x512 little-endian float32 matrix.

Note: not every inswapper build names this tensor ``emap``. The widely used
``inswapper_128_fp16.onnx`` (from ``hacksider/deep-live-cam``) stores it as a
512x512 float32 initialiser literally named ``initializer``. This script looks
for the tensor by name first, then falls back to the unique 512x512 float32
initialiser.

Usage:
    pip install onnx numpy
    python scripts/extract_emap.py models/inswapper_128.onnx models/inswapper_128_emap.bin
"""
from __future__ import annotations

import sys

import numpy as np
import onnx
from onnx import numpy_helper

SIZE = 512


def find_emap(model: onnx.ModelProto):
    """Return (tensor, reason) for the emap initialiser, or (None, None)."""
    for init in model.graph.initializer:
        if init.name == "emap":
            return init, "initializer named 'emap'"

    # Fallback: a unique 512x512 float32 initialiser.
    candidates = [i for i in model.graph.initializer if list(i.dims) == [SIZE, SIZE]]
    if len(candidates) == 1:
        return candidates[0], f"512x512 float32 initializer named '{candidates[0].name}'"

    # Last resort: any initialiser with exactly 512*512 float32 elements.
    by_size = [
        i for i in model.graph.initializer
        if int(np.prod(list(i.dims) or [0])) == SIZE * SIZE and i.data_type == 1
    ]
    if len(by_size) == 1:
        return by_size[0], f"{SIZE}x{SIZE} float32 initializer named '{by_size[0].name}'"
    return None, None


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    model_path, out_path = sys.argv[1], sys.argv[2]
    model = onnx.load(model_path)

    tensor, reason = find_emap(model)
    if tensor is None:
        names = ", ".join(i.name for i in model.graph.initializer)
        print(f"error: could not locate the emap tensor. Found: {names}", file=sys.stderr)
        return 1

    array = numpy_helper.to_array(tensor).astype("<f4").reshape(-1)
    if array.size != SIZE * SIZE:
        print(f"error: expected {SIZE * SIZE} elements, got {array.size}", file=sys.stderr)
        return 1

    array.tofile(out_path)
    print(f"selected {reason}")
    print(f"wrote {array.size * 4} bytes -> {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
