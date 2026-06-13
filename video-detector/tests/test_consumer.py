"""Consumer trace-propagation tests (mirror of audio-detector's TestTracePropagation —
the AMQP glue in consumer.py is an intentional copy, keep both suites in sync).
Run without the ML stack: uv pip install pika structlog prometheus-client opentelemetry-sdk pytest
"""
import json
from unittest.mock import MagicMock

import src.consumer as consumer

ANALYSIS_ID = "550e8400-e29b-41d4-a716-446655440000"


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
