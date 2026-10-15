"""Inference thread pools follow ONNX_INTRA_THREADS instead of always taking every host core."""
from unittest.mock import MagicMock

import pytest


@pytest.fixture
def runtimes(inference_module, monkeypatch):
    ort, torch = MagicMock(), MagicMock()
    monkeypatch.setattr(inference_module, "ort", ort)
    monkeypatch.setattr(inference_module, "torch", torch)
    monkeypatch.setattr(inference_module, "load_mel_cnn", MagicMock())
    return ort, torch


def test_configured_threads_size_both_runtimes(inference_module, runtimes, monkeypatch):
    ort, torch = runtimes
    monkeypatch.setattr(inference_module, "ONNX_INTRA_THREADS", 3)
    inference_module.AudioInference()
    options = ort.SessionOptions.return_value
    assert options.intra_op_num_threads == 3
    assert ort.InferenceSession.call_args.kwargs["sess_options"] is options
    torch.set_num_threads.assert_called_once_with(3)


def test_zero_keeps_library_defaults(inference_module, runtimes, monkeypatch):
    ort, torch = runtimes
    monkeypatch.setattr(inference_module, "ONNX_INTRA_THREADS", 0)
    inference_module.AudioInference()
    assert "intra_op_num_threads" not in vars(ort.SessionOptions.return_value)
    torch.set_num_threads.assert_not_called()
