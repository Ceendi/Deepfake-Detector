#!/usr/bin/env bash
# Generate a real ~5 MB MP4 (H.264 + AAC) that passes file-service validation (Tika magic bytes +
# ffprobe + MIME whitelist). A random blob would be rejected with 422 and never reach S3 — the
# benchmark would then measure the rejection path, not a real upload.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$DIR/sample-5mb.mp4}"
SECONDS_DUR="${2:-55}"
BITRATE="${3:-900k}"
ffmpeg -y -f lavfi -i "testsrc=duration=${SECONDS_DUR}:size=1280x720:rate=30" \
    -f lavfi -i "sine=frequency=440:duration=${SECONDS_DUR}" \
    -c:v libx264 -b:v "$BITRATE" -pix_fmt yuv420p -c:a aac -movflags +faststart -shortest "$OUT"
echo "Generated $OUT ($(du -h "$OUT" | cut -f1)). Tune bitrate/duration to hit ~5 MB."
