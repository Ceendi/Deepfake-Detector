"""Consumer contract + idempotency tests.

src.inference is stubbed before import so the suite needs no torch/onnx/insightface —
these tests cover the AMQP/Redis/S3 glue in consumer.py, not the model.
"""
import json
import sys
import types
from unittest.mock import MagicMock

import pytest
import redis as redis_lib

# Stub the heavy inference module before src.consumer imports it.
_fake_inference = types.ModuleType("src.inference")
_fake_inference.VideoInference = MagicMock()
sys.modules.setdefault("src.inference", _fake_inference)

import src.consumer as consumer  # noqa: E402

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
    monkeypatch.setattr(consumer, "s3_client", fake)
    return fake


@pytest.fixture
def inference(monkeypatch):
    fake = MagicMock()
    monkeypatch.setattr(consumer, "video_inference", fake)
    return fake


@pytest.fixture
def rds(monkeypatch):
    fake = MagicMock()
    fake.set.return_value = True  # SETNX acquired
    monkeypatch.setattr(consumer, "redis_client", fake)
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
        assert result["gradcam_keys"] == [
            f"{ANALYSIS_ID}/video/gradcam_frame_05.png",
            f"{ANALYSIS_ID}/video/gradcam_frame_09.png",
            f"{ANALYSIS_ID}/video/gradcam_frame_12.png",
        ]
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


class TestHandleMessageIdempotency:
    def _deliver(self, monkeypatch, rds, result=None, error=None):
        ch, method = MagicMock(), MagicMock(delivery_tag=7)
        fake_process = MagicMock()
        if error is not None:
            fake_process.side_effect = error
        else:
            fake_process.return_value = result or {"prob_fake": 0.9, "verdict": "FAKE",
                                                   "gradcam_keys": []}
        monkeypatch.setattr(consumer, "process", fake_process)
        consumer._handle_message(ch, method, MagicMock(), json.dumps(_task_msg()).encode())
        return ch, fake_process

    def test_dedup_key_is_per_source(self, monkeypatch, rds):
        self._deliver(monkeypatch, rds)

        assert rds.set.call_args[0][0] == f"processing:{ANALYSIS_ID}:video"
        assert rds.set.call_args[1] == {"nx": True, "ex": 3600}

    def test_duplicate_is_acked_without_processing(self, monkeypatch, rds):
        rds.set.return_value = None  # SETNX lost — someone is already processing

        ch, fake_process = self._deliver(monkeypatch, rds)

        fake_process.assert_not_called()
        ch.basic_publish.assert_not_called()
        ch.basic_ack.assert_called_once()

    def test_redis_down_fails_open_and_still_processes(self, monkeypatch, rds):
        rds.set.side_effect = redis_lib.RedisError("connection refused")

        ch, fake_process = self._deliver(monkeypatch, rds)

        fake_process.assert_called_once()
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "COMPLETED"
        ch.basic_ack.assert_called_once()

    def test_failure_publishes_failed_and_releases_dedup_key(self, monkeypatch, rds):
        ch, _ = self._deliver(monkeypatch, rds, error=RuntimeError("boom"))

        rds.delete.assert_called_once_with(f"processing:{ANALYSIS_ID}:video")
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "FAILED"
        assert published["error"]["code"] == "PROCESSING_ERROR"
        ch.basic_ack.assert_called_once()

    def test_pipeline_error_code_reaches_the_contract(self, monkeypatch, rds):
        # InferenceError subclasses carry contract codes (utils.py) — consumer must
        # publish them instead of flattening everything to PROCESSING_ERROR.
        from src.utils import NoFaceError

        ch, _ = self._deliver(monkeypatch, rds,
                              error=NoFaceError("face found in 1/16 frames"))

        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "FAILED"
        assert published["error"]["code"] == "NO_FACE_DETECTED"

    def test_success_keeps_dedup_key(self, monkeypatch, rds):
        ch, _ = self._deliver(monkeypatch, rds)

        rds.delete.assert_not_called()
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "COMPLETED"
