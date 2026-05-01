#!/bin/sh
# Phase 1: render s3.json from the template (env -> shared seaweedconfig volume).
# Uses sed (busybox built-in) instead of envsubst to avoid `apk add` at runtime.
set -eu

: "${S3_ADMIN_KEY:?missing}"
: "${S3_ADMIN_SECRET:?missing}"
: "${S3_FILE_SERVICE_KEY:?missing}"
: "${S3_FILE_SERVICE_SECRET:?missing}"
: "${S3_DETECTOR_KEY:?missing}"
: "${S3_DETECTOR_SECRET:?missing}"

sed \
  -e "s|\${S3_ADMIN_KEY}|${S3_ADMIN_KEY}|g" \
  -e "s|\${S3_ADMIN_SECRET}|${S3_ADMIN_SECRET}|g" \
  -e "s|\${S3_FILE_SERVICE_KEY}|${S3_FILE_SERVICE_KEY}|g" \
  -e "s|\${S3_FILE_SERVICE_SECRET}|${S3_FILE_SERVICE_SECRET}|g" \
  -e "s|\${S3_DETECTOR_KEY}|${S3_DETECTOR_KEY}|g" \
  -e "s|\${S3_DETECTOR_SECRET}|${S3_DETECTOR_SECRET}|g" \
  /s3.json.tmpl > /out/s3.json

chmod 0644 /out/s3.json

echo "SeaweedFS S3 identity config rendered to /etc/seaweedfs/s3.json"
