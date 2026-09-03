#!/usr/bin/env bash
# Downloads the offline Persian voice and the Tesseract Persian OCR model into the project.
# Run once from the project root:   bash tools/fetch-tts-model.sh
set -euo pipefail

VOICE="${VOICE:-vits-piper-fa_IR-amir-medium}"
TTS_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
OCR_URL="https://github.com/tesseract-ocr/tessdata_fast/raw/main/fas.traineddata"

TTS_DIR="app/src/main/assets/tts/fa"
OCR_DIR="app/src/main/assets/tessdata"

if [ ! -f settings.gradle.kts ]; then
  echo "Run this from the project root (where settings.gradle.kts lives)." >&2
  exit 1
fi

mkdir -p "$TTS_DIR" "$OCR_DIR" .cache

# ---- 1. Offline Persian TTS voice -------------------------------------------------------
if ls "$TTS_DIR"/*.onnx >/dev/null 2>&1; then
  echo "[skip] voice already present in $TTS_DIR"
else
  echo "[1/2] downloading voice $VOICE ..."
  curl -fL --retry 3 -o ".cache/${VOICE}.tar.bz2" "${TTS_BASE}/${VOICE}.tar.bz2"
  tar -xjf ".cache/${VOICE}.tar.bz2" -C .cache
  # Flatten the archive's top-level folder into assets/tts/fa
  cp -R ".cache/${VOICE}/." "$TTS_DIR/"
  rm -f "$TTS_DIR"/*.md "$TTS_DIR"/*.onnx.json 2>/dev/null || true
  echo "[ok] voice installed:"
  ls -la "$TTS_DIR"
fi

# ---- 2. Persian OCR model ---------------------------------------------------------------
if [ -s "$OCR_DIR/fas.traineddata" ]; then
  echo "[skip] fas.traineddata already present"
else
  echo "[2/2] downloading Tesseract Persian model ..."
  curl -fL --retry 3 -o "$OCR_DIR/fas.traineddata" "$OCR_URL"
  echo "[ok] $(du -h "$OCR_DIR/fas.traineddata" | cut -f1) -> $OCR_DIR/fas.traineddata"
fi

cat <<'EOF'

Remaining manual step: put the Sherpa-ONNX Android AAR in app/libs/
    https://github.com/k2-fsa/sherpa-onnx/releases
See app/libs/README.txt for details. Then:
    ./gradlew :app:installDebug
EOF
