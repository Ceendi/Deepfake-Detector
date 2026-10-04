FROM ubuntu:24.04@sha256:a853f94d226358a79c740cfc7bce0c289748f3fe3488d921d038ccd752c61b60 AS runtime-base
WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_NO_CACHE=1 \
    UV_PYTHON=/usr/bin/python3 \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    PATH="/opt/venv/bin:$PATH" \
    INSIGHTFACE_ROOT=/app/.insightface

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

COPY --chown=app:app pyproject.toml uv.lock ./
# Production image is CPU-only (ONNX CPUExecutionProvider). Pulling CPU wheels
# instead of CUDA drops ~4.5 GB (nvidia/* libs + triton) from the image.
# insightface nie publikuje wheeli (sdist z Cython) — build-essential tylko na czas
# instalacji, purge w tej samej warstwie zeby nie tuczyc obrazu.
RUN apt-get update && apt-get install -y --no-install-recommends build-essential python3-dev \
    && uv sync --frozen --no-dev --no-install-project \
    && apt-get purge -y build-essential python3-dev && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Pack RetinaFace (buffalo_l) zapieczony w obrazie na etapie builda — kontener nie
# moze zalezec od dostepu do sieci przy pierwszym zadaniu (demo offline).
RUN python -c "from insightface.app import FaceAnalysis; \
    FaceAnalysis(name='buffalo_l', root='/app/.insightface', allowed_modules=['detection'])" \
    && chown -R app:app /app/.insightface

COPY --chown=app:app src ./src
# training/ wnosi defy modelu (training/model.py) + checkpointy effnet_lstm
# (last.ckpt do atencji/Grad-CAM, backbone.onnx + temporal.onnx do inferencji);
# dataset cache / grid / venv wyciete w .dockerignore.
COPY --chown=app:app training ./training

USER app
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -sf http://localhost:8000/health || exit 1
CMD ["uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8000"]
