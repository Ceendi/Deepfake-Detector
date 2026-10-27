"""Inside a detector image: fetch the weights pinned in models.toml, load them and run them once.

Synthetic input only checks that the weights match the code (shapes, graphs, checkpoint keys);
detection quality is out of scope. Usage: python model_smoke.py audio|video
"""

import math
import subprocess
import sys

import numpy as np


def audio():
    from src.inference import AudioInference

    model = AudioInference()
    subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "lavfi",
                    "-i", "sine=frequency=440:duration=3", "/tmp/smoke.wav"], check=True)
    for mode in ("accurate", "fast"):
        result = model.analyze("/tmp/smoke.wav", mode=mode)
        assert result["verdict"] in ("REAL", "FAKE"), result
        print(f"audio {mode}: {result['verdict']} confidence={result['confidence']}")


def video():
    from src.inference import VideoInference

    model = VideoInference()
    clip = np.random.default_rng(0).standard_normal((16, 3, 224, 224)).astype(np.float32)
    feats = model.backbone_session.run(None, {"frames": clip})[0]
    assert feats.shape == (16, 1792), feats.shape
    logit = float(model.temporal_session.run(None, {"features": feats[None, ...]})[0].reshape(-1)[0])
    assert math.isfinite(logit), logit
    model._ensure_face_app()
    print(f"video: logit={logit:.4f}, face detector loaded")


if __name__ == "__main__":
    {"audio": audio, "video": video}[sys.argv[1]]()
