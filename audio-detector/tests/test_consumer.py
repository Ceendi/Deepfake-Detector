"""Audio adapter artifact and inference contract tests.

src.inference is stubbed before import so the suite needs no torch/onnx — these tests
cover the AMQP/Redis/S3 glue in consumer.py, not the model.
"""
import sys
import types
from unittest.mock import MagicMock

import pytest
from detector_runtime import AnalysisCancelled

# Stub the heavy inference module before src.consumer imports it.
_fake_inference = types.ModuleType("src.inference")
_fake_inference.AudioInference = MagicMock()
sys.modules.setdefault("src.inference", _fake_inference)

from src import consumer

ANALYSIS_ID = "550e8400-e29b-41d4-a716-446655440000"


def _task_msg():
    return {
        "analysis_id": ANALYSIS_ID,
        "file_bucket": "deepfake-uploads",
        "file_key": f"{ANALYSIS_ID}_test.wav",
        "correlation_id": "corr-1",
        "timestamp": "2026-06-12T10:00:00Z",
    }


def _analyze_result(gradcam_path=None):
    result = {
        "prob_fake": 0.91,
        "verdict": "FAKE",
        "confidence": 0.82,
        "model_version": "v1.2.0-accurate",
        "metadata": {"duration_seconds": 3.0},
    }
    if gradcam_path is not None:
        result["local_gradcam_path"] = str(gradcam_path)
    return result


@pytest.fixture
def s3(monkeypatch):
    fake = MagicMock()
    monkeypatch.setattr(consumer.runtime, "s3_client", fake)
    return fake


@pytest.fixture
def inference(monkeypatch):
    fake = MagicMock()
    monkeypatch.setattr(consumer, "audio_inference", fake)
    return fake


@pytest.fixture
def rds(monkeypatch):
    fake = MagicMock()
    fake.set.return_value = True
    fake.exists.return_value = 0  # no cancel flag
    monkeypatch.setattr(consumer.runtime, "redis_client", fake)
    return fake


class TestProcessGradcamContract:
    def test_publishes_bare_object_key_per_contract(self, s3, inference, tmp_path):
        png = tmp_path / "gradcam.png"
        png.write_bytes(b"\x89PNG")
        inference.analyze.return_value = _analyze_result(png)

        result = consumer.process(_task_msg())

        # {analysisId}/{source}/{name}.png — no URI scheme, no bucket prefix
        import re
        assert len(result["gradcam_keys"]) == 1
        assert re.fullmatch(rf"{ANALYSIS_ID}/audio/gradcam_[0-9a-f]{{32}}.png", result["gradcam_keys"][0])
        assert "gradcam_url" not in result
        bucket = s3.upload_file.call_args[0][1]
        key = s3.upload_file.call_args[0][2]
        assert bucket == "analysis-artifacts"
        assert key == result["gradcam_keys"][0]
        assert not png.exists()  # local temp cleaned up
        assert "local_gradcam_path" not in result

    def test_upload_failure_yields_empty_keys_not_a_crash(self, s3, inference, tmp_path):
        png = tmp_path / "gradcam.png"
        png.write_bytes(b"\x89PNG")
        inference.analyze.return_value = _analyze_result(png)
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
            used = set(re.findall(r'progress_callback\(\s*[\w+*/() ]+,\s*"([A-Z_]+)"', src))
            used |= set(re.findall(r'stage:\s*str\s*=\s*"([A-Z_]+)"', src))
            assert used <= allowed, f"{module} emits non-contract stages: {used - allowed}"


class TestInputTempCleanup:
    @pytest.mark.parametrize("abort", [AnalysisCancelled, RuntimeError])
    def test_downloaded_input_is_removed_when_analyze_aborts(self, s3, inference, abort):
        import pathlib
        paths = []
        def download(bucket, key, dst):
            paths.append(pathlib.Path(dst))
            paths[-1].write_bytes(b"x")
        s3.download_file.side_effect = download
        inference.analyze.side_effect = abort()

        with pytest.raises(abort):
            consumer.process(_task_msg())

        assert paths and not paths[0].exists()
        assert not paths[0].parent.exists()


@pytest.mark.parametrize("outcome,status", [
    ("completed", "success"), ("failed", "error"), ("cancelled", "cancelled"),
])
def test_runtime_retains_audio_metric_labels(monkeypatch, outcome, status):
    metric = MagicMock()
    monkeypatch.setattr(consumer, "PROCESSED_AUDIO_TOTAL", metric)
    consumer.runtime.record_outcome(outcome, {"verdict": "FAKE"})
    metric.labels.assert_called_once_with(status=status)
    metric.labels.return_value.inc.assert_called_once_with()
