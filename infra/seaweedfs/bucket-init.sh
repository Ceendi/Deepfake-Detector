#!/bin/sh
# Phase 2: create required buckets via S3 API. Idempotent (skip if exists).
# Must use the admin identity — file-service / detector keys are scoped to
# specific buckets via -s3.config and lack permission to create new ones.
set -eu

: "${S3_ADMIN_KEY:?missing}"
: "${S3_ADMIN_SECRET:?missing}"

export AWS_ACCESS_KEY_ID="$S3_ADMIN_KEY"
export AWS_SECRET_ACCESS_KEY="$S3_ADMIN_SECRET"
export AWS_DEFAULT_REGION="us-east-1"

ENDPOINT="http://seaweedfs:8333"

# Healthcheck only verifies the port; wait for signed requests to be accepted.
attempts=0
until aws --endpoint-url "$ENDPOINT" s3 ls >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if [ "$attempts" -gt 30 ]; then
    echo "ERROR: SeaweedFS S3 API not authenticating after 30s" >&2
    exit 1
  fi
  sleep 1
done

for bucket in deepfake-uploads analysis-artifacts; do
  if aws --endpoint-url "$ENDPOINT" s3 ls "s3://$bucket" >/dev/null 2>&1; then
    echo "Bucket s3://$bucket already exists"
  else
    aws --endpoint-url "$ENDPOINT" s3 mb "s3://$bucket"
    echo "Created bucket s3://$bucket"
  fi
done

echo "SeaweedFS bucket provisioning complete"
