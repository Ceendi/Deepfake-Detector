import importlib.util
import os
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest

# The runtime refuses to start without credentials; tests never reach S3 or RabbitMQ.
os.environ.setdefault("S3_ACCESS_KEY", "test")
os.environ.setdefault("S3_SECRET_KEY", "test")
os.environ.setdefault("RABBITMQ_PASSWORD", "test")


@pytest.fixture
def load_inference(monkeypatch):
    def load():
        for name in ("cv2", "torch", "onnxruntime", "training", "training.model",
                     "prometheus_client"):
            monkeypatch.setitem(sys.modules, name, MagicMock())
        spec = importlib.util.spec_from_file_location(
            "src._media_limits_inference", Path(__file__).parents[1] / "src/inference.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    return load
