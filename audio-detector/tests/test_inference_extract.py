"""_extract_audio failure surfacing.

Heavy ML deps (torch/onnx/matplotlib/...) are stubbed in sys.modules so the real
src.inference imports in the light test env; only the ffmpeg wrapper is exercised.
"""
from types import SimpleNamespace

import pytest


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
