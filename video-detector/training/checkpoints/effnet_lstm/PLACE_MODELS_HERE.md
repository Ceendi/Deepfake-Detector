# Put the video models here (EfficientNet-B4 + Bi-LSTM)

Copy into this directory from SharePoint: `MODELE-AUDIO-DETECTOR/Video-Detector/checkpoints/effnet_lstm/`

Required files (exact names — `video-detector/src/inference.py` looks them up verbatim):

- `backbone.onnx`   (+ optional sidecar `backbone.onnx.data`, if present in SharePoint)
- `temporal.onnx`
- `last.ckpt`       (needed for attention + Grad-CAM)

NOT needed (and excluded from the image via `video-detector/.dockerignore`):
`video-epoch*.ckpt` files and the `grid/` subdirectory. You can skip them.

The files are baked into the Docker image on `--build`, so they must be here
BEFORE you run `docker compose ... up --build`.
This file (PLACE_MODELS_HERE.md) is harmless and can be ignored.
