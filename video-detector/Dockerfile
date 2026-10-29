FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac AS runtime-base
WORKDIR /app

ENV HF_HUB_DISABLE_PROGRESS_BARS=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_NO_CACHE=1 \
    UV_PYTHON=/usr/bin/python3 \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    PATH="/opt/venv/bin:$PATH" \
    INSIGHTFACE_ROOT=/app/.insightface \
    MODEL_DIR=/models

# Runtime depy OpenCV; curl: healthcheck. insightface ma twarda zaleznosc na pelne
# opencv-python (nie headless), wiec mimo opencv-python-headless w pyproject obraz
# importuje cv2 wymagajace libGL.so.1 (libgl1) + libxcb.so.1 (libxcb1) + libglib2.0-0.
# Frame'y dekoduje ffmpeg zbundlowany w wheelu cv2 (apt-owy ffmpeg zbedny).
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 ca-certificates libglib2.0-0 libxcb1 libgl1 libstdc++6 libgomp1 curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.12.23@sha256:61d393e44e249f2e4b526b6c7ddcecce245946826e608e11c93ad4f5bba55b21 /uv /uvx /usr/local/bin/
RUN groupadd --system app && useradd --system --gid app --create-home app
USER app

FROM runtime-base AS runtime
USER root

COPY pyproject.toml uv.lock ./
COPY --from=detector_runtime /pyproject.toml /detector-runtime/pyproject.toml
# Production image is CPU-only (ONNX CPUExecutionProvider). Pulling CPU wheels
# instead of CUDA drops ~4.5 GB (nvidia/* libs + triton) from the image.
# insightface nie publikuje wheeli (sdist z Cython) — build-essential tylko na czas
# instalacji, purge w tej samej warstwie zeby nie tuczyc obrazu.
RUN apt-get update && apt-get install -y --no-install-recommends build-essential python3-dev \
    && uv sync --frozen --no-dev --no-install-project --no-install-package deepfake-detector-runtime \
    && apt-get purge -y build-essential python3-dev && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Ship only the verified detector; runtime must not download a mutable model pack.
COPY face-model.toml install_face_model.py ./
RUN python install_face_model.py

COPY --from=detector_runtime /detector_runtime /detector-runtime/detector_runtime
RUN uv pip install --python /opt/venv/bin/python --no-deps /detector-runtime

COPY src ./src
# training/ only brings the model definition (training/model.py); the weights are fetched at
# start from the commit pinned in models.toml into the /models volume.
COPY training ./training
COPY models.toml ./
RUN install -d -o app -g app /models

# uv leaves its advisory lock writable; runtime never installs packages.
RUN chmod go-w /opt/venv/.lock

USER app
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -sf http://localhost:8000/health || exit 1
CMD ["uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8000", "--log-config", "/detector-runtime/detector_runtime/uvicorn-log.json"]
