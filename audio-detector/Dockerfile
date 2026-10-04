FROM ubuntu:24.04@sha256:a853f94d226358a79c740cfc7bce0c289748f3fe3488d921d038ccd752c61b60 AS runtime-base
WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_NO_CACHE=1 \
    UV_PYTHON=/usr/bin/python3 \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    PATH="/opt/venv/bin:$PATH" \
    MPLCONFIGDIR=/tmp/matplotlib \
    HF_HOME=/tmp/huggingface

# ffmpeg: audio extraction; libglib2.0-0: librosa/soundfile runtime dep; curl: healthcheck.
# No libgl1 (OpenGL) — only OpenCV needs it, and this service has no cv2.
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 ca-certificates ffmpeg libglib2.0-0 curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.12.23@sha256:61d393e44e249f2e4b526b6c7ddcecce245946826e608e11c93ad4f5bba55b21 /uv /uvx /usr/local/bin/
RUN groupadd --system app && useradd --system --gid app --create-home app
USER app

FROM runtime-base AS runtime
USER root

COPY --chown=app:app pyproject.toml uv.lock ./
# Production image is CPU-only (ONNX CPUExecutionProvider). Pulling CPU wheels
# instead of CUDA drops ~4.5 GB (nvidia/* libs + triton) from the image.
RUN uv sync --frozen --no-dev --no-install-project

COPY --chown=app:app src ./src
COPY --chown=app:app training ./training

USER app
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
  CMD curl -sf http://localhost:8000/health || exit 1
CMD ["uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8000"]
