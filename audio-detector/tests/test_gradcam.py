"""Grad-CAM is explanatory: its failure keeps the verdict and never leaves model hooks."""
import subprocess
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest


def _analyzer(inference_module):
    analyzer = inference_module.AudioInference.__new__(inference_module.AudioInference)
    analyzer.mel_module = MagicMock()
    return analyzer


def test_hooks_are_removed_when_the_backward_pass_fails(inference_module):
    analyzer = _analyzer(inference_module)
    analyzer.mel_module.side_effect = RuntimeError("backward failed")
    layer = analyzer.mel_module.model.resnet.layer4
    with pytest.raises(RuntimeError):
        analyzer.generate_gradcam(MagicMock(), "unused.png")
    layer.register_forward_hook.return_value.remove.assert_called_once_with()
    layer.register_full_backward_hook.return_value.remove.assert_called_once_with()


def test_gradcam_failure_is_logged_and_leaves_no_partial_heatmap(inference_module, tmp_path):
    analyzer = _analyzer(inference_module)
    heatmap = tmp_path / "gradcam.png"

    def fail(chunk, path):
        Path(path).write_bytes(b"partial")
        raise RuntimeError("savefig failed")

    analyzer.generate_gradcam = fail
    analyzer._try_gradcam(MagicMock(), str(heatmap))
    assert not heatmap.exists()


def test_analysis_completes_without_heatmap_when_gradcam_fails(inference_module, monkeypatch,
                                                                tmp_path):
    analyzer = _analyzer(inference_module)
    analyzer._check_duration = MagicMock()
    analyzer._extract_audio = MagicMock()
    analyzer._load_wav = MagicMock(return_value=MagicMock(__len__=lambda self: 16000))
    def fail(chunk, path):
        Path(path).write_bytes(b"partial")
        raise RuntimeError("savefig failed")

    analyzer.generate_gradcam = MagicMock(side_effect=fail)
    analyzer.w2v2_session = MagicMock()
    analyzer.w2v2_session.run.return_value = [[[2.0]]]
    torch = inference_module.torch
    monkeypatch.setattr(torch, "sqrt", MagicMock(return_value=1.0))
    monkeypatch.setattr(inference_module.np, "exp", lambda value: 0.25, raising=False)
    result = analyzer.analyze(str(tmp_path / "input"), mode="accurate")
    analyzer.generate_gradcam.assert_called_once()
    assert result["verdict"] in {"FAKE", "REAL"}
    assert "local_gradcam_path" not in result
    assert not (tmp_path / "input_gradcam.png").exists()


def test_inference_import_failure_degrades_to_model_unavailable():
    # A broken ML stack must keep the process up and answer tasks, not crash-loop.
    script = r'''import sys, types
class Broken(types.ModuleType):
    def __getattr__(self, name):
        raise ImportError("libtorch.so: cannot open shared object file")
sys.modules["src.inference"] = Broken("src.inference")
from src import consumer
assert consumer.audio_inference is None
'''
    result = subprocess.run([sys.executable, "-c", script], cwd=Path(__file__).parents[1],
                            capture_output=True, text=True, timeout=20, check=False)
    assert result.returncode == 0, result.stdout + result.stderr
