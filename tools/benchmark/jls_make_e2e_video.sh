#!/usr/bin/env bash
set -euo pipefail

# Build an MP4 latency reference from jls_generate_e2e_signal.py output.
# Requires ffmpeg. Video flashes white for ~50 ms at each integer second;
# audio contains the sample-exact click, 10 s chirps, and steady tone.

DURATION="\${DURATION:-120}"
WAV="\${WAV:-jls_e2e_reference.wav}"
MANIFEST="\${MANIFEST:-jls_e2e_reference.json}"
OUT="\${OUT:-jls_e2e_reference.mp4}"

python3 "$(dirname "$0")/jls_generate_e2e_signal.py" \
  --duration-s "$DURATION" --out "$WAV" --manifest "$MANIFEST"

ffmpeg -y \
  -f lavfi -i "color=c=black:s=1280x720:r=60:d=$DURATION" \
  -i "$WAV" \
  -vf "drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='lt(mod(t\,1)\,0.05)'" \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p \
  -c:a aac -b:a 192k -shortest "$OUT"

echo "Created $OUT"
