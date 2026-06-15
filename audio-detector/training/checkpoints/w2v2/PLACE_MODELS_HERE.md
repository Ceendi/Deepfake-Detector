# Put the Wav2Vec2 models here ("accurate" mode)

Copy into this directory from SharePoint: `MODELE-AUDIO-DETECTOR/Audio-Detector/checkpoints/w2v2/`

Required files (exact names — `audio-detector/src/inference.py` looks them up verbatim):

- `w2v2.onnx`
- `w2v2.onnx.data`  ← weights sidecar (ONNX external-data format). If present in SharePoint, it MUST go here next to `w2v2.onnx`.

These files are baked into the Docker image on `--build` (whitelisted in `audio-detector/.dockerignore`),
so they must be here BEFORE you run `docker compose ... up --build`.
This file (PLACE_MODELS_HERE.md) is not copied into the image and can be ignored.
