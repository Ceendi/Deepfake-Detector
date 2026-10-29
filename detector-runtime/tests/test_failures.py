"""Failure classification: domain errors, storage outages, the task watchdog and Redis backoff."""
import json
from unittest.mock import MagicMock

import pika
import pytest
import redis as redis_lib
from boto3.exceptions import RetriesExceededError, S3UploadFailedError
from botocore.exceptions import (
    ClientError,
    ConnectTimeoutError,
    EndpointConnectionError,
    ReadTimeoutError,
)

from detector_runtime import Artifact, DetectorError
from detector_runtime import consumer as runtime_module
from detector_runtime.errors import StorageUnavailableError
from detector_runtime.storage import is_transient_storage_error

ANALYSIS_ID = "550e8400-e29b-41d4-a716-446655440000"
FILE_ID = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
NEUTRAL = {"code": "PROCESSING_ERROR",
           "message": "Nie udało się przetworzyć materiału. Spróbuj ponownie."}


def _body():
    return json.dumps({"analysis_id": ANALYSIS_ID, "file_bucket": "deepfake-uploads",
                       "file_key": f"{FILE_ID}_Jan Kowalski wywiad.mp4",
                       "correlation_id": "corr-1"}).encode()


def _deliver(runtime, ch=None):
    ch = ch or MagicMock()
    runtime.handle_message(ch, MagicMock(delivery_tag=7), MagicMock(headers={}), _body())
    return ch


def _published(ch):
    return [json.loads(call.kwargs["body"]) for call in ch.basic_publish.call_args_list]


def _client_error(code, status):
    return ClientError({"Error": {"Code": code, "Message": "x"},
                        "ResponseMetadata": {"HTTPStatusCode": status}}, "GetObject")


def test_failure_after_cancellation_is_acked_without_result(runtime):
    runtime.process_task.side_effect = RuntimeError("decode failed after cancel")
    runtime.redis_client.exists.side_effect = [0, 1]
    channel = _deliver(runtime)
    channel.basic_ack.assert_called_once_with(delivery_tag=7)
    channel.basic_publish.assert_not_called()
    runtime.record_outcome.assert_called_once_with("cancelled", None)


def test_s3_attempts_have_bounded_timeouts_without_hidden_retries(monkeypatch):
    from detector_runtime import storage

    client = MagicMock()
    monkeypatch.setattr(storage.boto3, "client", client)
    monkeypatch.setenv("S3_ACCESS_KEY", "test")
    monkeypatch.setenv("S3_SECRET_KEY", "test")
    storage.create_s3_client("http://localhost:8333")
    config = client.call_args.kwargs["config"]
    assert config.connect_timeout == 5
    assert config.read_timeout == 10
    assert config.retries == {"mode": "standard", "total_max_attempts": 1}


def _use_real_process(runtime, artifacts=lambda msg, result, attempt: []):
    def process(msg, progress_callback):
        return runtime.process(msg, model=object(), artifacts=artifacts,
                               analyze=lambda model, msg, path, progress: {
                                   "prob_fake": 0.9, "verdict": "FAKE"},
                               progress_callback=progress_callback)

    runtime.process_task = process


class TestDomainErrors:
    def test_foreign_numeric_code_is_published_as_neutral_processing_error(self, runtime):
        # cv2.error carries an integer code and a message with native details.
        error = RuntimeError("OpenCV(4.10) /io/opencv/modules/imgproc/resize.cpp: (-215)")
        error.code = -215
        runtime.process_task.side_effect = error
        ch = _deliver(runtime)
        assert _published(ch)[0]["error"] == NEUTRAL
        ch.basic_ack.assert_called_once_with(delivery_tag=7)

    def test_foreign_string_code_is_not_trusted(self, runtime):
        error = OSError("Error opening '/tmp/private/input': Format not recognised")
        error.code = "LIBSNDFILE"
        runtime.process_task.side_effect = error
        assert _published(_deliver(runtime))[0]["error"] == NEUTRAL

    def test_detector_error_publishes_its_code_and_message(self, runtime):
        class NoSpeech(DetectorError):
            code = "NO_SPEECH"

        runtime.process_task.side_effect = NoSpeech("Brak mowy.")
        assert _published(_deliver(runtime))[0]["error"] == {"code": "NO_SPEECH",
                                                            "message": "Brak mowy."}

    def test_failed_outcome_is_recorded_only_after_ack(self, runtime):
        runtime.process_task.side_effect = RuntimeError("boom")
        ch = MagicMock()
        ch.basic_ack.side_effect = lambda **kwargs: runtime.record_outcome.assert_not_called()
        _deliver(runtime, ch)
        runtime.record_outcome.assert_called_once_with("failed", None)

    def test_failed_outcome_is_not_recorded_when_publication_fails(self, runtime):
        runtime.process_task.side_effect = RuntimeError("boom")
        ch = MagicMock()
        ch.basic_publish.side_effect = pika.exceptions.NackError([])
        with pytest.raises(pika.exceptions.NackError):
            _deliver(runtime, ch)
        runtime.record_outcome.assert_not_called()


class TestStorageFailures:
    @pytest.mark.parametrize("error", [
        EndpointConnectionError(endpoint_url="http://seaweedfs:8333"),
        ConnectTimeoutError(endpoint_url="http://seaweedfs:8333"),
        ReadTimeoutError(endpoint_url="http://seaweedfs:8333"),
        RetriesExceededError(ReadTimeoutError(endpoint_url="http://seaweedfs:8333")),
        _client_error("503", 503),
        _client_error("InternalError", 500),
        _client_error("SlowDown", 503),
        _client_error("TooManyRequests", 429),
        _client_error("RequestTimeout", 400),
    ])
    def test_download_outage_redelivers_without_failed_result(self, runtime, error):
        runtime.s3_client.download_file.side_effect = error
        _use_real_process(runtime)
        ch = MagicMock()
        with pytest.raises(StorageUnavailableError):
            _deliver(runtime, ch)
        assert all("status" not in payload for payload in _published(ch))
        ch.basic_ack.assert_not_called()
        runtime.record_outcome.assert_not_called()

    @pytest.mark.parametrize("error", [
        _client_error("404", 404), _client_error("NoSuchKey", 404),
        _client_error("403", 403), _client_error("AccessDenied", 403),
    ])
    def test_missing_or_forbidden_input_fails_the_task(self, runtime, error):
        runtime.s3_client.download_file.side_effect = error
        _use_real_process(runtime)
        ch = _deliver(runtime)
        assert _published(ch)[-1]["error"] == NEUTRAL
        ch.basic_ack.assert_called_once_with(delivery_tag=7)

    def test_artifact_upload_outage_redelivers_instead_of_dropping_heatmaps(self, runtime, tmp_path):
        heatmap = tmp_path / "gradcam.png"
        heatmap.write_bytes(b"png")
        try:
            raise _client_error("500", 500)
        except ClientError:
            try:
                # boto3 wraps upload errors implicitly, without an explicit cause.
                raise S3UploadFailedError("Failed to upload")
            except S3UploadFailedError as wrapped:
                upload_error = wrapped
        runtime.s3_client.upload_file.side_effect = upload_error
        _use_real_process(runtime, artifacts=lambda msg, result, attempt: [
            Artifact(str(heatmap), f"{ANALYSIS_ID}/audio/gradcam_{attempt}.png")])
        ch = MagicMock()
        with pytest.raises(StorageUnavailableError):
            _deliver(runtime, ch)
        assert all("status" not in payload for payload in _published(ch))
        ch.basic_ack.assert_not_called()
        assert not heatmap.exists()

    def test_forbidden_artifact_upload_still_completes_without_heatmap(self, runtime, tmp_path):
        heatmap = tmp_path / "gradcam.png"
        heatmap.write_bytes(b"png")
        runtime.s3_client.upload_file.side_effect = _client_error("AccessDenied", 403)
        _use_real_process(runtime, artifacts=lambda msg, result, attempt: [
            Artifact(str(heatmap), f"{ANALYSIS_ID}/audio/gradcam_{attempt}.png")])
        payload = _published(_deliver(runtime))[-1]
        assert payload["status"] == "COMPLETED"
        assert payload["result"]["gradcam_keys"] == []

    @pytest.mark.parametrize("error", [ValueError("x"), OSError("disk full"),
                                       _client_error("400", 400)])
    def test_non_storage_errors_are_not_transient(self, error):
        assert not is_transient_storage_error(error)

    def test_reconnect_backoff_grows_after_storage_outages_and_resets_after_ack(self, runtime):
        outage = StorageUnavailableError("down")
        assert [runtime.reconnect_delay(outage) for _ in range(5)] == [5, 10, 20, 30, 30]
        assert runtime.reconnect_delay(pika.exceptions.AMQPConnectionError()) == 5
        runtime.process_task.return_value = {"prob_fake": 0.9, "verdict": "FAKE",
                                             "gradcam_keys": []}
        _deliver(runtime)
        assert runtime.reconnect_delay(outage) == 5

    def test_outage_log_omits_the_object_key(self, runtime):
        # Real botocore puts the full object URL, including the user's file name, in the error.
        runtime.s3_client.download_file.side_effect = EndpointConnectionError(
            endpoint_url=f"http://seaweedfs:8333/deepfake-uploads/{FILE_ID}_Jan Kowalski.mp4")
        _use_real_process(runtime)
        with pytest.raises(StorageUnavailableError):
            _deliver(runtime)
        logged = [call for method in (runtime.log.warning, runtime.log.exception)
                  for call in method.call_args_list]
        assert logged and "Kowalski" not in repr(logged)
        assert all("exc_info" not in call.kwargs for call in logged)

    def test_download_log_carries_only_the_file_uuid(self, runtime):
        _use_real_process(runtime)
        _deliver(runtime)
        transfer = runtime.s3_client.download_file.call_args.kwargs["Config"]
        assert transfer.num_download_attempts == 1
        download = [call for call in runtime.log.info.call_args_list
                    if call.args == ("downloading_file",)]
        assert download[0].kwargs == {"bucket": "deepfake-uploads", "file_id": FILE_ID}


class TestTaskWatchdog:
    def test_cancelled_hung_task_exits_after_grace_period(self, runtime, monkeypatch):
        now = [100.0]
        monkeypatch.setattr(runtime_module.time, "monotonic", lambda: now[0])
        runtime._task_started = 40.0
        runtime._task_context = {"analysis_id": ANALYSIS_ID}
        runtime.exit_process = MagicMock()
        runtime.redis_client.exists.return_value = 1
        now[0] = 99.9
        runtime.check_task_deadline()
        runtime.redis_client.exists.assert_not_called()
        now[0] = 100.0
        runtime.check_task_deadline()
        runtime.redis_client.exists.assert_called_once_with(f"cancel:{ANALYSIS_ID}")
        runtime.exit_process.assert_called_once_with(1)

    def test_watchdog_cancel_checks_are_throttled_and_fail_open(self, runtime, monkeypatch):
        now = [100.0]
        monkeypatch.setattr(runtime_module.time, "monotonic", lambda: now[0])
        runtime._task_started = 40.0
        runtime._task_context = {"analysis_id": ANALYSIS_ID}
        runtime.exit_process = MagicMock()
        runtime.redis_client.exists.side_effect = redis_lib.RedisError("offline")
        runtime.check_task_deadline()
        now[0] = 109.9
        runtime.check_task_deadline()
        assert runtime.redis_client.exists.call_count == 1
        now[0] = 110.0
        runtime.check_task_deadline()
        assert runtime.redis_client.exists.call_count == 2
        runtime.exit_process.assert_not_called()

    def test_task_over_hard_limit_marks_down_and_exits(self, runtime, monkeypatch):
        runtime.exit_process = MagicMock()
        runtime.health_state["ok"] = True
        monkeypatch.setattr(runtime_module.time, "monotonic", lambda: 10_000.0)
        runtime._task_started = 10_000.0 - runtime.task_hard_limit_seconds - 1
        runtime.check_task_deadline()
        assert runtime.health_state["ok"] is False
        runtime.exit_process.assert_called_once_with(1)

    def test_task_within_limit_or_idle_keeps_running(self, runtime, monkeypatch):
        runtime.exit_process = MagicMock()
        monkeypatch.setattr(runtime_module.time, "monotonic", lambda: 10_000.0)
        runtime.check_task_deadline()
        runtime._task_started = 10_000.0 - runtime.task_hard_limit_seconds
        runtime.check_task_deadline()
        runtime.exit_process.assert_not_called()

    def test_run_task_tracks_only_the_running_attempt(self, runtime):
        seen = []
        runtime.handle_message = lambda *args: seen.append(runtime._task_started)
        runtime.run_task(MagicMock(), MagicMock(), MagicMock(), b"{}")
        assert seen[0] is not None
        assert runtime._task_started is None

    def test_default_hard_limit_and_invalid_values(self, monkeypatch):
        monkeypatch.delenv("DETECTOR_TASK_HARD_LIMIT_SECONDS", raising=False)
        assert runtime_module._task_hard_limit_seconds() == 3600
        for value in ["0", "-1", "nan", "inf"]:
            monkeypatch.setenv("DETECTOR_TASK_HARD_LIMIT_SECONDS", value)
            with pytest.raises(ValueError):
                runtime_module._task_hard_limit_seconds()


def test_redis_outage_skips_cancel_checks_for_five_seconds(runtime, monkeypatch):
    now = [100.0]
    monkeypatch.setattr(runtime_module.time, "monotonic", lambda: now[0])
    runtime.redis_client.exists.side_effect = redis_lib.RedisError("timeout")
    assert runtime.is_cancelled(ANALYSIS_ID) is False
    now[0] = 104.9
    assert runtime.is_cancelled(ANALYSIS_ID) is False
    assert runtime.redis_client.exists.call_count == 1
    runtime.redis_client.exists.side_effect = None
    runtime.redis_client.exists.return_value = 1
    now[0] = 105.0
    assert runtime.is_cancelled(ANALYSIS_ID) is True


def test_missing_broker_password_never_falls_back_to_a_default(runtime, monkeypatch):
    monkeypatch.delenv("RABBITMQ_PASSWORD")
    with pytest.raises(RuntimeError, match="RABBITMQ_PASSWORD"):
        type(runtime)(source=runtime.source, queue=runtime.queue, log=runtime.log,
                      tracer=runtime.tracer, s3_client=runtime.s3_client,
                      redis_client=runtime.redis_client, process_task=runtime.process_task,
                      record_outcome=runtime.record_outcome)


def test_missing_storage_credentials_never_fall_back_to_defaults(monkeypatch):
    from detector_runtime import create_s3_client

    monkeypatch.delenv("S3_ACCESS_KEY", raising=False)
    monkeypatch.setenv("S3_SECRET_KEY", "secret")
    with pytest.raises(RuntimeError, match="S3_ACCESS_KEY"):
        create_s3_client("http://localhost:8333")


@pytest.mark.parametrize("transient", [True, False])
def test_storage_failure_traces_and_logs_omit_private_names(runtime, transient):
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import SimpleSpanProcessor
    from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    runtime.tracer = provider.get_tracer("privacy")
    private = "Jan Kowalski wywiad.mp4"
    runtime.s3_client.download_file.side_effect = (
        EndpointConnectionError(endpoint_url=f"http://s3/uploads/{private}") if transient else
        ClientError({"Error": {"Code": "AccessDenied", "Message": private},
                     "ResponseMetadata": {"HTTPStatusCode": 403}}, "GetObject"))
    _use_real_process(runtime)
    if transient:
        with pytest.raises(StorageUnavailableError):
            _deliver(runtime)
    else:
        _deliver(runtime)
    spans = exporter.get_finished_spans()
    assert spans and all(private not in span.to_json() for span in spans)
    assert spans[0].status.status_code.name == "ERROR"
    assert private not in repr(runtime.log.mock_calls)
    runtime.log.exception.assert_not_called()
    provider.shutdown()


def test_watchdog_logs_running_analysis_context_from_another_thread(runtime):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event

    entered, release = Event(), Event()
    def handle(*args):
        entered.set()
        assert release.wait(5)
    runtime.handle_message = handle
    runtime.exit_process = MagicMock()
    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(runtime.run_task, MagicMock(), MagicMock(), MagicMock(), _body())
        try:
            assert entered.wait(5)
            runtime._task_started -= runtime.task_hard_limit_seconds + 1
            runtime.check_task_deadline()
            fields = runtime.log.critical.call_args.kwargs
            assert fields["analysis_id"] == ANALYSIS_ID
            assert fields["correlation_id"] == "corr-1"
        finally:
            release.set()
        future.result(timeout=5)


@pytest.mark.parametrize("failure", ["progress", "result", "ack"])
def test_transport_failure_keeps_safe_error_status(runtime, failure):
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import SimpleSpanProcessor
    from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    runtime.tracer = provider.get_tracer("transport")
    private = "Jan Kowalski wywiad.mp4"
    channel = MagicMock()
    error = pika.exceptions.AMQPConnectionError(private)
    if failure == "ack":
        channel.basic_ack.side_effect = error
    else:
        channel.basic_publish.side_effect = error
    def process(msg, progress_callback):
        if failure == "progress":
            progress_callback(50, "INFERENCE")
        return {"prob_fake": .9, "verdict": "FAKE", "gradcam_keys": []}
    runtime.process_task = process
    with pytest.raises(pika.exceptions.AMQPError):
        _deliver(runtime, channel)
    span, = exporter.get_finished_spans()
    assert span.status.status_code.name == "ERROR"
    assert span.attributes["error.type"] == "AMQPConnectionError"
    assert private not in span.to_json()
    provider.shutdown()
