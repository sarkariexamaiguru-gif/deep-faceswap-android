#!/usr/bin/env python3
"""Extract the inswapper ``emap`` matrix from an ONNX model into a raw binary.

The Rust swapper expects a sidecar file ``inswapper_128_emap.bin`` next to
``inswapper_128.onnx``: a 512x512 little-endian float32 matrix (the ``emap``
initialiser inside the ONNX graph).

Usage:
    pip install onnx numpy
    python scripts/extract_emap.py models/inswapper_128.onnx models/inswapper_128_emap.bin
"""
from __future__ import annotations

import sys

import numpy as np
import onnx
from onnx import numpy_helper


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    model_path, out_path = sys.argv[1], sys.argv[2]
    model = onnx.load(model_path)

    for init in model.graph.initializer:
        if init.name == "emap":
            array = numpy_helper.to_array(init).astype("<f4")
            flat = array.reshape(-1)
            if flat.size != 512 * 512:
                print(
                    f"error: expected 512x512 emap, got {flat.size} elements",
                    file=sys.stderr,
                )
                return 1
            flat.tofile(out_path)
            print(f"wrote {flat.size * 4} bytes -> {out_path}")
            return 0

    names = ", ".join(i.name for i in model.graph.initializer)
    print(f"error: no initializer named 'emap'. Found: {names}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
