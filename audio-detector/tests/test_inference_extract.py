"""_extract_audio failure surfacing.

Heavy ML deps (torch/onnx/matplotlib/...) are stubbed in sys.modules so the real
src.inference imports in the light test env; only the ffmpeg wrapper is exercised.
"""
import importlib
import sys
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

_HEAVY = [
    "torch", "numpy", "soundfile", "onnxruntime",
    "matplotlib", "matplotlib.pyplot",
    "training", "training.train_mel",
]


@pytest.fixture(scope="module")
def inference_module():
    saved = {name: sys.modules.get(name) for name in _HEAVY + ["src.inference"]}
    for name in _HEAVY:
        sys.modules[name] = MagicMock()
    sys.modules.pop("src.inference", None)  # test_consumer may have stubbed it
    module = importlib.import_module("src.inference")
    yield module
    for name, original in saved.items():
        if original is not None:
            sys.modules[name] = original
        else:
            sys.modules.pop(name, None)


def _instance(inference_module):
    # Skip __init__ (loads model checkpoints) — only the ffmpeg wrapper is under test.
    return inference_module.AudioInference.__new__(inference_module.AudioInference)


def test_ffmpeg_failure_raises_with_stderr_tail(inference_module, monkeypatch):
    monkeypatch.setattr(inference_module.subprocess, "run", lambda *a, **kw: SimpleNamespace(
        returncode=1, stderr=b"Invalid data found when processing input"))

    with pytest.raises(RuntimeError) as e:
        _instance(inference_module)._extract_audio("in.bin", 16000, "out.wav")

    assert "rc=1" in str(e.value)
    assert "Invalid data found" in str(e.value)


def test_ffmpeg_success_is_silent(inference_module, monkeypatch):
    monkeypatch.setattr(inference_module.subprocess, "run", lambda *a, **kw: SimpleNamespace(
        returncode=0, stderr=b""))

    _instance(inference_module)._extract_audio("in.bin", 16000, "out.wav")
