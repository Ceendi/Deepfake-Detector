"""Consumer contract + idempotency tests.

src.inference is stubbed before import so the suite needs no torch/onnx — these tests
cover the AMQP/Redis/S3 glue in consumer.py, not the model.
"""
import json
import sys
import types
from unittest.mock import MagicMock

import pytest
import redis as redis_lib

# Stub the heavy inference module before src.consumer imports it.
_fake_inference = types.ModuleType("src.inference")
_fake_inference.AudioInference = MagicMock()
sys.modules.setdefault("src.inference", _fake_inference)

import src.consumer as consumer  # noqa: E402

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
    monkeypatch.setattr(consumer, "s3_client", fake)
    return fake


@pytest.fixture
def inference(monkeypatch):
    fake = MagicMock()
    monkeypatch.setattr(consumer, "audio_inference", fake)
    return fake


@pytest.fixture
def rds(monkeypatch):
    fake = MagicMock()
    fake.set.return_value = True  # SETNX acquired
    fake.exists.return_value = 0  # no cancel flag
    monkeypatch.setattr(consumer, "redis_client", fake)
    return fake


class TestProcessGradcamContract:
    def test_publishes_bare_object_key_per_contract(self, s3, inference, tmp_path):
        png = tmp_path / "gradcam.png"
        png.write_bytes(b"\x89PNG")
        inference.analyze.return_value = _analyze_result(png)

        result = consumer.process(_task_msg())

        # {analysisId}/{source}/{name}.png — no URI scheme, no bucket prefix
        assert result["gradcam_keys"] == [f"{ANALYSIS_ID}/audio/gradcam.png"]
        assert "gradcam_url" not in result
        bucket = s3.upload_file.call_args[0][1]
        key = s3.upload_file.call_args[0][2]
        assert (bucket, key) == ("analysis-artifacts", f"{ANALYSIS_ID}/audio/gradcam.png")
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


class TestTracePropagation:
    """traceparent flows AMQP-header -> consumer span -> published results (amqp-messages.md)."""

    INCOMING_TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
    TRACEPARENT = f"00-{INCOMING_TRACE_ID}-b7ad6b7169203331-01"

    def _deliver(self, monkeypatch, headers, error=None):
        ch, method = MagicMock(), MagicMock(delivery_tag=7)
        fake_process = MagicMock()
        if error is not None:
            fake_process.side_effect = error
        else:
            fake_process.return_value = {"prob_fake": 0.9, "verdict": "FAKE", "gradcam_keys": []}
        monkeypatch.setattr(consumer, "process", fake_process)
        rds = MagicMock()
        rds.set.return_value = True
        rds.exists.return_value = 0
        monkeypatch.setattr(consumer, "redis_client", rds)
        props = MagicMock()
        props.headers = headers
        consumer._handle_message(ch, method, props, json.dumps(_task_msg()).encode())
        return ch

    def test_result_publish_continues_incoming_trace(self, monkeypatch):
        ch = self._deliver(monkeypatch, headers={"traceparent": self.TRACEPARENT})

        published = ch.basic_publish.call_args[1]["properties"].headers
        assert published["traceparent"].split("-")[1] == self.INCOMING_TRACE_ID
        # child span, not an echo of the orchestrator's span id
        assert published["traceparent"].split("-")[2] != "b7ad6b7169203331"

    def test_failure_result_also_carries_the_trace(self, monkeypatch):
        ch = self._deliver(monkeypatch, headers={"traceparent": self.TRACEPARENT},
                           error=RuntimeError("boom"))

        published = ch.basic_publish.call_args[1]["properties"].headers
        assert published["traceparent"].split("-")[1] == self.INCOMING_TRACE_ID

    def test_missing_header_roots_a_new_trace(self, monkeypatch):
        ch = self._deliver(monkeypatch, headers=None)

        published = ch.basic_publish.call_args[1]["properties"].headers
        assert "traceparent" in published
        assert published["traceparent"].split("-")[1] != self.INCOMING_TRACE_ID


class TestCancellation:
    """Cooperative cancel via the Redis flag cancel:{analysis_id} (amqp-messages.md):
    checked at task pickup and on every progress tick; ack + drop, never a FAILED result."""

    def _deliver(self, monkeypatch, process=None):
        ch, method = MagicMock(), MagicMock(delivery_tag=7)
        fake_process = process or MagicMock(
            return_value={"prob_fake": 0.9, "verdict": "FAKE", "gradcam_keys": []})
        monkeypatch.setattr(consumer, "process", fake_process)
        consumer._handle_message(ch, method, MagicMock(), json.dumps(_task_msg()).encode())
        return ch, fake_process

    def test_cancelled_while_queued_is_acked_without_any_work(self, monkeypatch, rds):
        rds.exists.return_value = 1

        ch, fake_process = self._deliver(monkeypatch)

        rds.exists.assert_called_with(f"cancel:{ANALYSIS_ID}")
        fake_process.assert_not_called()
        ch.basic_publish.assert_not_called()
        ch.basic_ack.assert_called_once()
        rds.set.assert_not_called()  # dedup key never touched

    def test_cancelled_mid_inference_acks_without_publishing_a_result(self, monkeypatch, rds):
        # Flag appears after the task started: pickup check and first tick are clean,
        # the second tick sees the flag and aborts.
        rds.exists.side_effect = [0, 0, 1]

        def fake_process(msg, progress_callback=None):
            progress_callback(0, "LOADING")
            progress_callback(40, "INFERENCE")
            raise AssertionError("unreachable — the cancelled tick must raise")

        ch, _ = self._deliver(monkeypatch, process=fake_process)

        bodies = [json.loads(c[1]["body"]) for c in ch.basic_publish.call_args_list]
        assert all("status" not in b for b in bodies)  # progress only — no COMPLETED/FAILED
        ch.basic_ack.assert_called_once()
        rds.delete.assert_called_once_with(f"processing:{ANALYSIS_ID}:audio")

    def test_redis_down_on_cancel_check_fails_open(self, monkeypatch, rds):
        rds.exists.side_effect = redis_lib.RedisError("connection refused")
        rds.set.return_value = True

        ch, fake_process = self._deliver(monkeypatch)

        fake_process.assert_called_once()
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "COMPLETED"
        ch.basic_ack.assert_called_once()


class TestInputTempCleanup:
    @pytest.mark.parametrize("abort", [consumer.AnalysisCancelled, RuntimeError])
    def test_downloaded_input_is_removed_when_analyze_aborts(self, s3, inference, abort):
        import pathlib
        import tempfile
        input_path = pathlib.Path(tempfile.gettempdir()) / f"{ANALYSIS_ID}_input"
        s3.download_file.side_effect = lambda bucket, key, dst: pathlib.Path(dst).write_bytes(b"x")
        inference.analyze.side_effect = abort()

        with pytest.raises(abort):
            consumer.process(_task_msg())

        assert not input_path.exists()


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

        assert rds.set.call_args[0][0] == f"processing:{ANALYSIS_ID}:audio"
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

        rds.delete.assert_called_once_with(f"processing:{ANALYSIS_ID}:audio")
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "FAILED"
        assert published["error"]["code"] == "PROCESSING_ERROR"
        ch.basic_ack.assert_called_once()

    def test_success_keeps_dedup_key(self, monkeypatch, rds):
        ch, _ = self._deliver(monkeypatch, rds)

        rds.delete.assert_not_called()
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "COMPLETED"
