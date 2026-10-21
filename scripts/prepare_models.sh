#!/usr/bin/env bash
#
# Convenience helper: download the models, extract the emap sidecar, and print
# the adb commands to push everything onto a connected Android device.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODELS="$ROOT/models"
BUFFALO="$MODELS/buffalo_l"
TMP="$(mktemp -d)"

mkdir -p "$BUFFALO"

echo "==> buffalo_l models"
if [ ! -f "$BUFFALO/det_10g.onnx" ] || [ ! -f "$BUFFALO/w600k_r50.onnx" ] || [ ! -f "$BUFFALO/2d106det.onnx" ]; then
    curl -L "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip" -o "$TMP/buffalo_l.zip"
    unzip -j "$TMP/buffalo_l.zip" det_10g.onnx w600k_r50.onnx 2d106det.onnx -d "$BUFFALO"
else
    echo "    already present"
fi

echo "==> inswapper_128.onnx"
if [ ! -f "$MODELS/inswapper_128.onnx" ]; then
    curl -L "https://huggingface.co/hacksider/deep-live-cam/resolve/main/inswapper_128_fp16.onnx" \
        -o "$MODELS/inswapper_128.onnx"
else
    echo "    already present"
fi

echo "==> emap sidecar"
if [ ! -f "$MODELS/inswapper_128_emap.bin" ]; then
    python3 "$ROOT/scripts/extract_emap.py" \
        "$MODELS/inswapper_128.onnx" "$MODELS/inswapper_128_emap.bin"
else
    echo "    already present"
fi

rm -rf "$TMP"

cat <<EOF

All models ready in: $MODELS

Push them to a connected device (app must have been launched once):

  PKG=com.deepfaceswap.android
  DEST=/sdcard/Android/data/\$PKG/files/models
  adb shell mkdir -p \$DEST/buffalo_l
  adb push $BUFFALO/det_10g.onnx    \$DEST/buffalo_l/
  adb push $BUFFALO/w600k_r50.onnx  \$DEST/buffalo_l/
  adb push $BUFFALO/2d106det.onnx   \$DEST/buffalo_l/
  adb push $MODELS/inswapper_128.onnx      \$DEST/
  adb push $MODELS/inswapper_128_emap.bin  \$DEST/
EOF
