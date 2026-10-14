"""Shared heavy-module stubs for tests that exercise the production inference wiring."""
import importlib
import os
import sys
from unittest.mock import MagicMock

import pytest

# The runtime refuses to start without credentials; tests never reach S3 or RabbitMQ.
os.environ.setdefault("S3_ACCESS_KEY", "test")
os.environ.setdefault("S3_SECRET_KEY", "test")
os.environ.setdefault("RABBITMQ_PASSWORD", "test")

_HEAVY = [
    "torch", "soundfile", "onnxruntime",
    "matplotlib", "matplotlib.figure", "matplotlib.image",
    "src.models.mel_cnn",  # imports torchaudio/torchvision at module load
]


@pytest.fixture(scope="session")
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


