"""Shared heavy-module stubs for tests that exercise the production inference wiring."""
import importlib
import sys
from unittest.mock import MagicMock

import pytest

_HEAVY = [
    "torch", "soundfile", "onnxruntime",
    "matplotlib", "matplotlib.pyplot",
    "training", "training.train_mel",
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


