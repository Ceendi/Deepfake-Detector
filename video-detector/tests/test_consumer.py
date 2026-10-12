"""Video adapter artifact and inference contract tests.

src.inference is stubbed before import so the suite needs no torch/onnx/insightface —
these tests cover the AMQP/Redis/S3/OTel glue in consumer.py, not the model.
Run without the ML stack:
  python -m pip install -r audio-detector/tests/requirements-light.txt (from repository root)
"""
import sys
import types
from unittest.mock import MagicMock

import pytest

# Stub the heavy inference module before src.consumer imports it.
_fake_inference = types.ModuleType("src.inference")
_fake_inference.VideoInference = MagicMock()
sys.modules.setdefault("src.inference", _fake_inference)

from src import consumer

ANALYSIS_ID = "550e8400-e29b-41d4-a716-446655440000"


def _task_msg():
    return {
        "analysis_id": ANALYSIS_ID,
        "file_bucket": "deepfake-uploads",
        "file_key": f"{ANALYSIS_ID}_test.mp4",
        "correlation_id": "corr-1",
        "timestamp": "2026-06-12T10:00:00Z",
    }


def _analyze_result(gradcam_paths=None):
    return {
        "prob_fake": 0.87,
        "verdict": "FAKE",
        "confidence": 0.74,
        "model_version": "effnetb4-bilstm-v1.0.0",
        "metadata": {"duration_seconds": 12.4, "frames_sampled": 16},
        "local_gradcam_paths": gradcam_paths or [],
    }


@pytest.fixture
def s3(monkeypatch):
    fake = MagicMock()
    monkeypatch.setattr(consumer.runtime, "s3_client", fake)
    return fake


@pytest.fixture
def inference(monkeypatch):
    fake = MagicMock()
    monkeypatch.setattr(consumer, "video_inference", fake)
    return fake


@pytest.fixture
def rds(monkeypatch):
    fake = MagicMock()
    fake.set.return_value = True
    fake.exists.return_value = 0   # no cancel flag
    monkeypatch.setattr(consumer.runtime, "redis_client", fake)
    return fake


class TestProcessGradcamContract:
    def test_publishes_bare_object_keys_per_contract(self, s3, inference, tmp_path):
        pngs = []
        for t in (5, 9, 12):
            p = tmp_path / f"gradcam_frame_{t:02d}.png"
            p.write_bytes(b"\x89PNG")
            pngs.append((str(p), t))
        inference.analyze.return_value = _analyze_result(pngs)

        result = consumer.process(_task_msg())

        # {analysisId}/{source}/{name}.png — no URI scheme, no bucket prefix
        import re
        assert len(result["gradcam_keys"]) == 3
        for key, index in zip(result["gradcam_keys"], [5, 9, 12], strict=True):
            assert re.fullmatch(rf"{ANALYSIS_ID}/video/gradcam_frame_{index:02d}_[0-9a-f]{{32}}.png", key)
        assert "local_gradcam_paths" not in result
        buckets = {call[0][1] for call in s3.upload_file.call_args_list}
        assert buckets == {"analysis-artifacts"}
        assert all(not p.exists() for p in tmp_path.iterdir())  # local temps cleaned up

    def test_upload_failure_yields_empty_keys_not_a_crash(self, s3, inference, tmp_path):
        png = tmp_path / "gradcam_frame_05.png"
        png.write_bytes(b"\x89PNG")
        inference.analyze.return_value = _analyze_result([(str(png), 5)])
        s3.upload_file.side_effect = RuntimeError("s3 down")

        result = consumer.process(_task_msg())

        assert result["gradcam_keys"] == []
        assert not png.exists()

    def test_no_heatmap_yields_empty_keys(self, s3, inference):
        inference.analyze.return_value = _analyze_result()

        result = consumer.process(_task_msg())

        assert result["gradcam_keys"] == []
        s3.upload_file.assert_not_called()


class TestProgressContract:
    def test_loading_start_ping_fires_before_s3_download(self, s3, inference):
        inference.analyze.return_value = _analyze_result()
        calls = []
        s3.download_file.side_effect = lambda *a, **kw: calls.append("download")

        def cb(pct, stage="INFERENCE", details=None):
            calls.append((pct, stage))

        consumer.process(_task_msg(), progress_callback=cb)

        assert calls[0] == (0, "LOADING")
        assert calls[1] == "download"

    def test_sources_emit_only_contract_stages(self):
        # Conformance grep over both modules: the stage set is closed (amqp-messages.md);
        # a detector-specific name would silently break clients switching on stage.
        import pathlib
        import re
        allowed = {"LOADING", "PREPROCESSING", "INFERENCE", "POSTPROCESSING"}
        for module in ("consumer.py", "inference.py"):
            src = (pathlib.Path(__file__).parent.parent / "src" / module).read_text(encoding="utf-8")
            used = set(re.findall(r'\b(?:progress_callback|cb)\(\s*[^,"]+,\s*"([A-Z_]+)"', src))
            used |= set(re.findall(r'stage:\s*str\s*=\s*"([A-Z_]+)"', src))
            assert used <= allowed, f"{module} emits non-contract stages: {used - allowed}"


@pytest.mark.parametrize("outcome,verdict,label", [
    ("completed", "FAKE", "fake"), ("completed", "REAL", "real"),
    ("failed", None, "failed"), ("cancelled", None, "cancelled"),
])
def test_runtime_retains_video_metric_labels(monkeypatch, outcome, verdict, label):
    metric = MagicMock()
    monkeypatch.setattr(consumer, "DETECTOR_RESULTS", metric)
    consumer.runtime.record_outcome(outcome, {"verdict": verdict})
    metric.labels.assert_called_once_with(consumer.SOURCE, label)
    metric.labels.return_value.inc.assert_called_once_with()


def test_runtime_retains_video_latency_labels(monkeypatch):
    metric = MagicMock()
    monkeypatch.setattr(consumer, "INFERENCE_LATENCY", metric)
    assert consumer.runtime.inference_timer() is metric.labels.return_value.time.return_value
    metric.labels.assert_called_once_with(consumer.SOURCE)
