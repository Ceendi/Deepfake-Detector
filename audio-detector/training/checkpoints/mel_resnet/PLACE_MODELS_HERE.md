# Put the Mel-ResNet model here ("fast" mode)

Copy into this directory from SharePoint: `MODELE-AUDIO-DETECTOR/Audio-Detector/checkpoints/mel_resnet/`

Required file (EXACT name — it is hardcoded in `audio-detector/src/inference.py`):

- `mel_model-epoch=49-val_eer=0.0816.ckpt`

⚠️ The name must match character-for-character (including the `=` signs). A different name = model load error.

The file is baked into the Docker image on `--build` (whitelisted in `audio-detector/.dockerignore`),
so it must be here BEFORE you run `docker compose ... up --build`.
This file (PLACE_MODELS_HERE.md) is not copied into the image and can be ignored.
