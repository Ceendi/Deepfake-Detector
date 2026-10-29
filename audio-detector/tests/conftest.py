"""Shared heavy-module stubs for tests that exercise the production inference wiring."""
import importlib
import os
import sys
import types
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


# Each test owns its stubs and collectors, including attribute mutations on MagicMock.
@pytest.fixture
def inference_module():
    import prometheus_client

    import src

    original_attribute = getattr(src, "inference", None)
    saved = {name: sys.modules.get(name) for name in _HEAVY + ["src.inference"]}
    for name in _HEAVY:
        sys.modules[name] = MagicMock()
    sys.modules.pop("src.inference", None)  # test_consumer may have stubbed it
    registry = prometheus_client.CollectorRegistry()
    histogram, gauge = prometheus_client.Histogram, prometheus_client.Gauge
    try:
        with pytest.MonkeyPatch.context() as patch:
            patch.setattr(prometheus_client, "Histogram",
                          lambda *args, **kw: histogram(*args, **kw, registry=registry))
            patch.setattr(prometheus_client, "Gauge",
                          lambda *args, **kw: gauge(*args, **kw, registry=registry))
            module = importlib.import_module("src.inference")
        yield module
    finally:
        if original_attribute is None:
            src.__dict__.pop("inference", None)
        else:
            src.inference = original_attribute
        for name, original in saved.items():
            if original is not None:
                sys.modules[name] = original
            else:
                sys.modules.pop(name, None)


@pytest.fixture(scope="session", autouse=True)
def consumer_modules():
    import src

    names = ["src.inference", "src.consumer", "src.main"]
    saved = {name: sys.modules.get(name) for name in names}
    attributes = {name: getattr(src, name, None) for name in ("inference", "consumer", "main")}
    stub = types.ModuleType("src.inference")
    stub.AudioInference = MagicMock()
    sys.modules["src.inference"] = stub
    consumer = importlib.import_module("src.consumer")
    main = importlib.import_module("src.main")
    yield consumer, main
    for name, original in saved.items():
        if original is None:
            sys.modules.pop(name, None)
        else:
            sys.modules[name] = original
    for name, original in attributes.items():
        if original is None:
            src.__dict__.pop(name, None)
        else:
            setattr(src, name, original)
