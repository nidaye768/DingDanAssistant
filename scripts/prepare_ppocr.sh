#!/usr/bin/env bash
set -euo pipefail

# Build the official PaddleOCR Android SDK as an AAR and package PP-OCRv6_small models.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 https://github.com/PaddlePaddle/PaddleOCR.git "$WORK/PaddleOCR"
SDK="$WORK/PaddleOCR/deploy/ppocr-android"
ASSETS="$SDK/ppocr-sdk/src/main/assets/models"
mkdir -p "$ASSETS/det" "$ASSETS/rec"

curl -fL --retry 3 \
  "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx" \
  -o "$ASSETS/det/inference.onnx"
curl -fL --retry 3 \
  "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx" \
  -o "$ASSETS/rec/inference.onnx"
curl -fL --retry 3 \
  "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml" \
  -o "$ASSETS/rec/inference.yml"

test "$(stat -c%s "$ASSETS/det/inference.onnx")" -gt 1000000
test "$(stat -c%s "$ASSETS/rec/inference.onnx")" -gt 1000000
test -s "$ASSETS/rec/inference.yml"

(
  cd "$SDK"
  chmod +x ./gradlew
  ./gradlew :ppocr-sdk:assembleRelease --no-daemon --stacktrace
)
mkdir -p app/libs
cp "$SDK/ppocr-sdk/build/outputs/aar/ppocr-sdk-release.aar" app/libs/
