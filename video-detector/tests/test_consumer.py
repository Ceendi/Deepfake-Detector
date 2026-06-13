"""Consumer trace-propagation + cancellation tests (mirror of audio-detector's suites —
the AMQP glue in consumer.py is an intentional copy, keep both suites in sync).
Run without the ML stack: uv pip install pika structlog redis prometheus-client opentelemetry-sdk pytest
"""
import json
from unittest.mock import MagicMock

import pytest
import redis as redis_lib

import src.consumer as consumer

ANALYSIS_ID = "550e8400-e29b-41d4-a716-446655440000"


@pytest.fixture
def rds(monkeypatch):
    fake = MagicMock()
    fake.exists.return_value = 0  # no cancel flag
    monkeypatch.setattr(consumer, "redis_client", fake)
    return fake


def _task_msg():
    return {
        "analysis_id": ANALYSIS_ID,
        "file_bucket": "deepfake-uploads",
        "file_key": f"{ANALYSIS_ID}_test.mp4",
        "correlation_id": "corr-1",
        "timestamp": "2026-06-13T10:00:00Z",
    }


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

    def test_cancelled_mid_inference_acks_without_publishing_a_result(self, monkeypatch, rds):
        # Flag appears after the task started: pickup check and the LOADING tick are clean,
        # the next tick sees the flag and aborts.
        rds.exists.side_effect = [0, 0, 1]

        def fake_process(msg, progress_callback=None):
            progress_callback(50, "INFERENCE")
            raise AssertionError("unreachable — the cancelled tick must raise")

        ch, _ = self._deliver(monkeypatch, process=fake_process)

        bodies = [json.loads(c[1]["body"]) for c in ch.basic_publish.call_args_list]
        assert all("status" not in b for b in bodies)  # progress only — no COMPLETED/FAILED
        ch.basic_ack.assert_called_once()

    def test_redis_down_on_cancel_check_fails_open(self, monkeypatch, rds):
        rds.exists.side_effect = redis_lib.RedisError("connection refused")

        ch, fake_process = self._deliver(monkeypatch)

        fake_process.assert_called_once()
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "COMPLETED"
        ch.basic_ack.assert_called_once()
