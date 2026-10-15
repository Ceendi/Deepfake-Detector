"""Collect real Prometheus samples without loading audio checkpoints."""
import importlib.util
import math
import re
import sys
import tomllib
from functools import partial
from pathlib import Path
from unittest.mock import MagicMock

import prometheus_client
import pytest
from detector_runtime.consumer import _task_hard_limit_seconds
from prometheus_client import CollectorRegistry


@pytest.mark.parametrize("hard_limit", ["3600", "5400.5"])
def test_audio_duration_histogram_covers_task_limit(monkeypatch, hard_limit):
    monkeypatch.setenv("DETECTOR_TASK_HARD_LIMIT_SECONDS", hard_limit)
    registry = CollectorRegistry()
    for collector in ("Gauge", "Histogram"):
        monkeypatch.setattr(prometheus_client, collector,
                            partial(getattr(prometheus_client, collector), registry=registry))
    for name in ("torch", "soundfile", "onnxruntime", "matplotlib", "matplotlib.figure",
                 "matplotlib.image", "src.models.mel_cnn"):
        monkeypatch.setitem(sys.modules, name, MagicMock())
    spec = importlib.util.spec_from_file_location(
        "src._metrics_inference", Path(__file__).parents[1] / "src/inference.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.INFERENCE_TIME.observe(120)
    samples = next(iter(module.INFERENCE_TIME.collect())).samples
    finite = [sample for sample in samples if sample.name.endswith("_bucket")
              and math.isfinite(float(sample.labels["le"]))]
    assert max(float(sample.labels["le"]) for sample in finite) >= _task_hard_limit_seconds()
    assert any(float(sample.labels["le"]) >= 120 and sample.value == 1 for sample in finite)
    assert next(sample.value for sample in samples if sample.name.endswith("_count")) == 1


def test_image_dependencies_are_exact_versions_from_lock():
    service = Path(__file__).resolve().parents[1]
    project = tomllib.loads((service / "pyproject.toml").read_text())
    lock = tomllib.loads((service / "uv.lock").read_text())
    locked = {(package["name"], package["version"].split("+")[0])
              for package in lock["package"]}
    for dependency in project["project"]["dependencies"]:
        if dependency == "deepfake-detector-runtime":
            continue
        match = re.fullmatch(r"([\w-]+)(?:\[[\w,]+\])?==([\d.]+)", dependency)
        assert match is not None, f"{service}: runtime dependency is not exact: {dependency}"
        name, version = match.groups()
        assert (name.replace("_", "-").lower(), version) in locked
