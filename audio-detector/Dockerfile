FROM python:3.12-slim
WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    PATH="/opt/venv/bin:$PATH" \
    MPLCONFIGDIR=/tmp/matplotlib \
    HF_HOME=/tmp/huggingface

# ffmpeg: audio extraction; libglib2.0-0: librosa/soundfile runtime dep; curl: healthcheck.
# No libgl1 (OpenGL) — only OpenCV needs it, and this service has no cv2.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ffmpeg libglib2.0-0 curl \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir uv==0.12.23
RUN addgroup --system app && adduser --system --ingroup app app

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
