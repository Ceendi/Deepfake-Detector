"""Shared delivery guards, cancellation and W3C propagation without the ML stack."""
import json
from unittest.mock import MagicMock

import pytest
import redis as redis_lib

ANALYSIS_ID = "550e8400-e29b-41d4-a716-446655440000"


def _task_msg():
    return {"analysis_id": ANALYSIS_ID, "file_bucket": "deepfake-uploads",
            "file_key": f"{ANALYSIS_ID}_test.wav", "correlation_id": "corr-1",
            "timestamp": "2026-06-12T10:00:00Z"}


class TestTracePropagation:
    """traceparent flows AMQP-header -> consumer span -> published results (amqp-messages.md)."""

    INCOMING_TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
    TRACEPARENT = f"00-{INCOMING_TRACE_ID}-b7ad6b7169203331-01"

    def _deliver(self, runtime, monkeypatch, headers, error=None):
        ch, method = MagicMock(), MagicMock(delivery_tag=7)
        fake_process = MagicMock()
        if error is not None:
            fake_process.side_effect = error
        else:
            fake_process.return_value = {"prob_fake": 0.9, "verdict": "FAKE", "gradcam_keys": []}
        monkeypatch.setattr(runtime, "process_task", fake_process)
        rds = MagicMock()
        rds.set.return_value = True
        rds.exists.return_value = 0
        monkeypatch.setattr(runtime, "redis_client", rds)
        props = MagicMock()
        props.headers = headers
        runtime.handle_message(ch, method, props, json.dumps(_task_msg()).encode())
        return ch

    def test_result_publish_continues_incoming_trace(self, runtime, monkeypatch):
        ch = self._deliver(runtime, monkeypatch, headers={"traceparent": self.TRACEPARENT})

        published = ch.basic_publish.call_args[1]["properties"].headers
        assert published["traceparent"].split("-")[1] == self.INCOMING_TRACE_ID
        # child span, not an echo of the orchestrator's span id
        assert published["traceparent"].split("-")[2] != "b7ad6b7169203331"

    def test_failure_result_also_carries_the_trace(self, runtime, monkeypatch):
        ch = self._deliver(runtime, monkeypatch, headers={"traceparent": self.TRACEPARENT},
                           error=RuntimeError("boom"))

        published = ch.basic_publish.call_args[1]["properties"].headers
        assert published["traceparent"].split("-")[1] == self.INCOMING_TRACE_ID

    def test_missing_header_roots_a_new_trace(self, runtime, monkeypatch):
        ch = self._deliver(runtime, monkeypatch, headers=None)

        published = ch.basic_publish.call_args[1]["properties"].headers
        assert "traceparent" in published
        assert published["traceparent"].split("-")[1] != self.INCOMING_TRACE_ID


class TestCancellation:
    """Cooperative cancel via the Redis flag cancel:{analysis_id} (amqp-messages.md):
    checked at task pickup and on every progress tick; ack + drop, never a FAILED result."""

    def _deliver(self, runtime, monkeypatch, process=None):
        ch, method = MagicMock(), MagicMock(delivery_tag=7)
        fake_process = process or MagicMock(
            return_value={"prob_fake": 0.9, "verdict": "FAKE", "gradcam_keys": []})
        monkeypatch.setattr(runtime, "process_task", fake_process)
        runtime.handle_message(ch, method, MagicMock(), json.dumps(_task_msg()).encode())
        return ch, fake_process

    def test_cancelled_while_queued_is_acked_without_any_work(self, runtime, monkeypatch, rds):
        rds.exists.return_value = 1

        ch, fake_process = self._deliver(runtime, monkeypatch)

        rds.exists.assert_called_with(f"cancel:{ANALYSIS_ID}")
        fake_process.assert_not_called()
        ch.basic_publish.assert_not_called()
        ch.basic_ack.assert_called_once()
        rds.set.assert_not_called()  # dedup key never touched

    def test_cancelled_mid_inference_acks_without_publishing_a_result(self, runtime, monkeypatch, rds):
        # Flag appears after the task started: pickup check and first tick are clean,
        # the second tick sees the flag and aborts.
        rds.exists.side_effect = [0, 0, 1]

        def fake_process(msg, progress_callback=None):
            progress_callback(0, "LOADING")
            progress_callback(40, "INFERENCE")
            raise AssertionError("unreachable — the cancelled tick must raise")

        ch, _ = self._deliver(runtime, monkeypatch, process=fake_process)

        bodies = [json.loads(c[1]["body"]) for c in ch.basic_publish.call_args_list]
        assert all("status" not in b for b in bodies)  # progress only — no COMPLETED/FAILED
        ch.basic_ack.assert_called_once()
        rds.delete.assert_not_called()

    def test_redis_down_on_cancel_check_fails_open(self, runtime, monkeypatch, rds):
        rds.exists.side_effect = redis_lib.RedisError("connection refused")
        rds.set.return_value = True

        ch, fake_process = self._deliver(runtime, monkeypatch)

        fake_process.assert_called_once()
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "COMPLETED"
        ch.basic_ack.assert_called_once()


class TestHandleMessageIdempotency:
    def _deliver(self, runtime, monkeypatch, rds, result=None, error=None):
        ch, method = MagicMock(), MagicMock(delivery_tag=7)
        fake_process = MagicMock()
        if error is not None:
            fake_process.side_effect = error
        else:
            fake_process.return_value = result or {"prob_fake": 0.9, "verdict": "FAKE",
                                                   "gradcam_keys": []}
        monkeypatch.setattr(runtime, "process_task", fake_process)
        runtime.handle_message(ch, method, MagicMock(), json.dumps(_task_msg()).encode())
        return ch, fake_process

    def test_stale_processing_marker_never_skips_work(self, runtime, monkeypatch, rds):
        rds.set.return_value = None
        ch, fake_process = self._deliver(runtime, monkeypatch, rds)
        fake_process.assert_called_once()
        rds.set.assert_not_called()
        rds.delete.assert_not_called()
        assert json.loads(ch.basic_publish.call_args[1]["body"])["status"] == "COMPLETED"
        ch.basic_ack.assert_called_once()

    def test_redis_down_fails_open_and_still_processes(self, runtime, monkeypatch, rds):
        rds.exists.side_effect = redis_lib.RedisError("connection refused")

        ch, fake_process = self._deliver(runtime, monkeypatch, rds)

        fake_process.assert_called_once()
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "COMPLETED"
        ch.basic_ack.assert_called_once()

    def test_failure_publishes_failed_without_touching_processing_markers(self, runtime, monkeypatch, rds):
        ch, _ = self._deliver(runtime, monkeypatch, rds, error=RuntimeError("boom"))

        rds.delete.assert_not_called()
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "FAILED"
        assert published["error"]["code"] == "PROCESSING_ERROR"
        ch.basic_ack.assert_called_once()

    def test_success_does_not_create_processing_marker(self, runtime, monkeypatch, rds):
        ch, _ = self._deliver(runtime, monkeypatch, rds)

        rds.delete.assert_not_called()
        published = json.loads(ch.basic_publish.call_args[1]["body"])
        assert published["status"] == "COMPLETED"


class TestPublicationFailure:
    @pytest.mark.parametrize("failed_publication", [1, 2])
    def test_transport_failure_never_acks_or_publishes_failed(self, runtime, monkeypatch, rds,
                                                            failed_publication):
        import pika
        ch = MagicMock()
        count = 0

        def publish(**kwargs):
            nonlocal count
            count += 1
            if count == failed_publication:
                raise pika.exceptions.NackError([])

        def process(msg, progress_callback):
            progress_callback(0, "LOADING")
            return {"prob_fake": 0.9, "verdict": "FAKE", "gradcam_keys": []}

        ch.basic_publish.side_effect = publish
        monkeypatch.setattr(runtime, "process_task", process)
        props = MagicMock(headers={})
        with pytest.raises(pika.exceptions.NackError):
            runtime.handle_message(ch, MagicMock(delivery_tag=7), props,
                                     json.dumps(_task_msg()).encode())
        ch.basic_ack.assert_not_called()
        assert all(json.loads(call.kwargs["body"]).get("status") != "FAILED"
                   for call in ch.basic_publish.call_args_list)

    def test_ack_failure_after_confirm_does_not_publish_failed(self, runtime, monkeypatch, rds):
        import pika
        ch = MagicMock()
        ch.basic_ack.side_effect = pika.exceptions.StreamLostError("lost before ack")
        monkeypatch.setattr(runtime, "process_task", MagicMock(return_value={
            "prob_fake": 0.9, "verdict": "FAKE", "gradcam_keys": []}))
        with pytest.raises(pika.exceptions.StreamLostError):
            runtime.handle_message(ch, MagicMock(delivery_tag=7), MagicMock(headers={}),
                                     json.dumps(_task_msg()).encode())
        assert ch.basic_publish.call_count == 1
        assert json.loads(ch.basic_publish.call_args.kwargs["body"])["status"] == "COMPLETED"


@pytest.mark.parametrize("code", ["VIDEO_DECODE_FAILED", "NO_FACE_DETECTED", "MODEL_UNAVAILABLE"])
def test_domain_error_code_reaches_the_contract(runtime, code):
    from detector_runtime import DetectorError

    class PipelineError(DetectorError):
        pass

    error = PipelineError("controlled domain error")
    error.code = code
    runtime.process_task.side_effect = error
    ch = MagicMock()
    runtime.handle_message(ch, MagicMock(delivery_tag=7), MagicMock(headers={}),
                           json.dumps(_task_msg()).encode())
    payload = json.loads(ch.basic_publish.call_args.kwargs["body"])
    assert payload["error"] == {"code": code, "message": str(error)}
    ch.basic_ack.assert_called_once_with(delivery_tag=7)


@pytest.mark.parametrize("body", [b"{", b"{}", b"null", b"[]", b"\xff\xfe{}", b'"id"',
                                  b"[" * 100_000 + b"]" * 100_000],
                         ids=["invalid-json", "missing-id", "null", "array", "invalid-utf8",
                              "string", "deeply-nested"])
def test_malformed_task_goes_to_dlq_without_work(runtime, body):
    ch = MagicMock()
    runtime.handle_message(ch, MagicMock(delivery_tag=7), MagicMock(headers={}), body)
    ch.basic_nack.assert_called_once_with(delivery_tag=7, requeue=False)
    ch.basic_ack.assert_not_called()
    ch.basic_publish.assert_not_called()
    runtime.process_task.assert_not_called()


def test_progress_and_result_confirm_before_ack_with_context(runtime):
    import structlog

    events = []
    details = {"current_segment": 1, "total_segments": 2}

    def process(msg, progress_callback):
        progress_callback(50, "INFERENCE", details)
        return {"prob_fake": 0.9, "verdict": "FAKE", "gradcam_keys": []}

    def publish(**kwargs):
        ch.basic_ack.assert_not_called()
        assert kwargs["exchange"] == "analysis.exchange"
        assert kwargs["mandatory"] is True
        assert kwargs["properties"].content_type == "application/json"
        assert kwargs["properties"].delivery_mode == 2
        context = structlog.contextvars.get_contextvars()
        assert context["analysis_id"] == ANALYSIS_ID
        assert context["correlation_id"] == "corr-1"
        assert context["source"] == runtime.source
        assert len(context["trace_id"]) == 32
        events.append((kwargs["routing_key"], json.loads(kwargs["body"])))

    runtime.process_task = process
    ch = MagicMock()
    ch.basic_publish.side_effect = publish
    ch.basic_ack.side_effect = lambda **kwargs: events.append("ack")
    runtime.handle_message(ch, MagicMock(delivery_tag=7, redelivered=True),
                           MagicMock(headers={}), json.dumps(_task_msg()).encode())
    assert events[0] == ("analysis.progress", {
        "analysis_id": ANALYSIS_ID, "correlation_id": "corr-1", "source": runtime.source,
        "progress": 50, "stage": "INFERENCE", "details": details,
    })
    assert events[1][0] == "analysis.results"
    assert events[1][1]["status"] == "COMPLETED"
    assert events[2] == "ack"
    runtime.record_outcome.assert_called_once_with("completed", events[1][1]["result"])
    assert structlog.contextvars.get_contextvars() == {}



@pytest.mark.parametrize("source", ["audio", "video"])
def test_generic_failure_does_not_publish_private_paths(runtime, source):
    runtime.source = source
    runtime.process_task.side_effect = OSError("Cannot read /tmp/private/input.wav")
    channel = MagicMock()
    runtime.handle_message(channel, MagicMock(delivery_tag=7), MagicMock(headers={}),
                           json.dumps(_task_msg()).encode())
    payload = json.loads(channel.basic_publish.call_args.kwargs["body"])
    assert payload["error"] == {"code": "PROCESSING_ERROR",
                                "message": "Nie udało się przetworzyć materiału. Spróbuj ponownie."}
    channel.basic_ack.assert_called_once_with(delivery_tag=7)


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_non_finite_result_fails_with_strict_json(runtime, value):
    def reject_constant(name):
        raise AssertionError(f"non-standard JSON constant {name}")

    runtime.process_task.return_value = {"prob_fake": value, "verdict": "FAKE",
                                         "metadata": {"fps": value}}
    channel = MagicMock()
    runtime.handle_message(channel, MagicMock(delivery_tag=7), MagicMock(headers={}),
                           json.dumps(_task_msg()).encode())
    payload = json.loads(channel.basic_publish.call_args.kwargs["body"],
                         parse_constant=reject_constant)
    assert payload["status"] == "FAILED"
    assert payload["result"] is None
    assert payload["error"]["code"] == "PROCESSING_ERROR"
    channel.basic_ack.assert_called_once_with(delivery_tag=7)
    runtime.record_outcome.assert_called_once_with("failed", None)


def test_publish_refuses_non_finite_numbers(runtime):
    with pytest.raises(ValueError):
        runtime.publish(MagicMock(), "analysis.progress", {"progress": float("nan")})
