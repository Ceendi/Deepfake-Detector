#!/usr/bin/env bash
# Exercise an actual detector image without production checkpoints or network access.
set -euo pipefail

service=${1:?Pass audio-detector or video-detector}
image=${2:?Pass the built image reference}
platform=${3:-linux/amd64}
case "$service" in
  audio-detector|video-detector) ;;
  *) echo "Unsupported detector: $service" >&2; exit 2 ;;
esac
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
context=$(mktemp -d)
test_image="deepfake-detector-test:$(basename "$context" | tr '[:upper:]' '[:lower:]')"
smoke_container="deepfake-detector-smoke-$(basename "$context" | tr '[:upper:]' '[:lower:]')"
cleanup() {
  docker rm -f "$smoke_container" >/dev/null 2>&1 || true
  docker image rm "$test_image" >/dev/null 2>&1 || true
  rm -rf "$context"
}
trap cleanup EXIT

cat > "$context/Dockerfile" <<'DOCKERFILE'
ARG RUNTIME_IMAGE
FROM ${RUNTIME_IMAGE}
USER root
RUN uv pip install --python /opt/venv/bin/python pytest==9.1.1
USER app
DOCKERFILE

docker build --platform "$platform" --build-arg "RUNTIME_IMAGE=$image" --tag "$test_image" "$context"
# Verify that the production user can import the real CPU libraries.
docker run --rm --platform "$platform" --network none --entrypoint python "$image" -c \
  'import os, torch, torchvision, lightning; assert os.getuid() != 0; assert torch.version.cuda is None'
# The audio wire fixture is shared with Java; its read-only mount mirrors the repository path.
test_mounts=(
  --mount "type=bind,source=$repo_root/$service/tests,target=/app/tests,readonly"
  --mount "type=bind,source=$repo_root/$service/training/tests,target=/app/training/tests,readonly"
  --mount "type=bind,source=$repo_root/detector-runtime/tests,target=/app/runtime-tests,readonly"
)
if [[ "$service" == "audio-detector" ]]; then
  test_mounts+=(--mount "type=bind,source=$repo_root/orchestrator/src/test/resources/contracts,target=/orchestrator/src/test/resources/contracts,readonly")
fi
for suite in tests runtime-tests training/tests; do
  # Separate processes prevent the consumer tests' ML stubs from affecting real ML checks.
  docker run --rm --platform "$platform" --network none \
    --env OMP_NUM_THREADS=2 --env MKL_NUM_THREADS=2 \
    "${test_mounts[@]}" \
    --entrypoint python "$test_image" -m pytest -q -p no:cacheprovider \
    --basetemp=/tmp/detector-check "$suite"
done

docker run --detach --name "$smoke_container" --platform "$platform" --network none \
  --env AWS_EC2_METADATA_DISABLED=true --env S3_ACCESS_KEY=test --env S3_SECRET_KEY=test \
  --env OMP_NUM_THREADS=2 --env MKL_NUM_THREADS=2 "$image" >/dev/null
for attempt in {1..60}; do
  if docker exec "$smoke_container" curl --silent --output /tmp/health.json \
      --write-out '%{http_code}' http://localhost:8000/health > "$context/health-status"; then
    status=$(cat "$context/health-status")
    case "$status" in
      200|503)
        docker exec "$smoke_container" python -c \
          'import json; assert json.load(open("/tmp/health.json"))["status"] in {"UP", "DOWN"}'
        exit 0
        ;;
    esac
  fi
  sleep 1
done
docker logs "$smoke_container"
echo "Detector image did not serve /health" >&2
exit 1
