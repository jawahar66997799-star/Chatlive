#!/usr/bin/env bash
set -euo pipefail
# Build a visual+audio end-to-end reference video.
# Requires ffmpeg. Audio must be generated first with jls_generate_e2e_signal.py.
AUDIO="${1:-jls_e2e_reference.wav}"
OUT="${2:-jls_e2e_reference.mp4}"
DURATION="${DURATION:-120}"

ffmpeg -y \
  -f lavfi -i "color=c=black:s=1280x720:r=60:d=${DURATION}" \
  -i "$AUDIO" \
  -vf "drawbox=x=0:y=0:w=iw:h=ih:color=white@1:t=fill:enable='lt(mod(t,1),0.05)',drawtext=text='JLS E2E REFERENCE':fontcolor=white:fontsize=52:x=(w-text_w)/2:y=h*0.42,drawtext=text='%{pts\\:hms}':fontcolor=white:fontsize=42:x=(w-text_w)/2:y=h*0.55" \
  -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p \
  -c:a aac -b:a 256k -shortest "$OUT"

echo "Wrote $OUT"
