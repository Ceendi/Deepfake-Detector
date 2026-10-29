"""Inside a detector image: fetch the weights pinned in models.toml, load them and run them once.

Synthetic input only checks that the weights match the code (shapes, graphs, checkpoint keys);
detection quality is out of scope. Usage: python model_smoke.py audio|video
"""

import math
import os
import subprocess
import sys
import tempfile


def check_remote_files(manifest, *, url=None, metadata=None):
    if url is None or metadata is None:
        from huggingface_hub import get_hf_file_metadata, hf_hub_url

        url = url or hf_hub_url
        metadata = metadata or get_hf_file_metadata
    for filename in manifest['files']:
        metadata(url(repo_id=manifest['repo'], revision=manifest['revision'], filename=filename))


def audio():
    from src.inference import AudioInference

    model = AudioInference()
    with tempfile.TemporaryDirectory() as workdir:
        tone = os.path.join(workdir, "tone.wav")
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "lavfi",
                        "-i", "sine=frequency=440:duration=3", tone], check=True)
        for mode in ("accurate", "fast"):
            result = model.analyze(tone, mode=mode)
            assert result["verdict"] in ("REAL", "FAKE"), result
            print(f"audio {mode}: {result['verdict']} confidence={result['confidence']}")


def video():
    import numpy as np
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
    from detector_runtime.model_files import load_manifest

    check_remote_files(load_manifest('/app/models.toml'))
    {"audio": audio, "video": video}[sys.argv[1]]()
