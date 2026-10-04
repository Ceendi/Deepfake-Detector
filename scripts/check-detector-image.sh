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
cleanup() {
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
for suite in tests training/tests; do
  # Separate processes prevent the consumer tests' ML stubs from affecting real ML checks.
  docker run --rm --platform "$platform" --network none \
    --env OMP_NUM_THREADS=2 --env MKL_NUM_THREADS=2 \
    --mount "type=bind,source=$repo_root/$service/tests,target=/app/tests,readonly" \
    --mount "type=bind,source=$repo_root/$service/training/tests,target=/app/training/tests,readonly" \
    --entrypoint python "$test_image" -m pytest -q -p no:cacheprovider \
    --basetemp=/tmp/detector-check "$suite"
done
