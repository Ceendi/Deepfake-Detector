import json
from concurrent.futures import ThreadPoolExecutor
from queue import Empty, Queue
from threading import Event, get_ident
from unittest.mock import MagicMock

import pika
import pytest
import structlog

from detector_runtime import consumer as runtime_module
from detector_runtime.connection import ConnectionChannel


class StopConsumer(BaseException):
    pass


def test_blocked_inference_keeps_io_beating_and_channel_operations_on_owner(runtime, monkeypatch):
    owner = get_ident()
    started, release, completed = Event(), Event(), Event()
    callbacks = Queue()
    connection = MagicMock()
    channel = connection.channel.return_value
    connection.add_callback_threadsafe.side_effect = callbacks.put
    monkeypatch.setattr(runtime_module.pika, "BlockingConnection", lambda _: connection)
    inference_threads, publications = [], []
    upstream_trace_id = "0af7651916cd43dd8448eb211c80319c"
    initial_beat = None

    def process(msg, progress_callback):
        inference_threads.append(get_ident())
        started.set()
        assert release.wait(timeout=3), "I/O did not continue during blocked inference"
        progress_callback(50, "INFERENCE")
        return {"prob_fake": 0.8, "verdict": "FAKE", "gradcam_keys": []}

    def publish(**kwargs):
        assert get_ident() == owner
        assert structlog.contextvars.get_contextvars()["correlation_id"] == "thread-test"
        assert kwargs["properties"].headers["traceparent"].split("-")[1] == upstream_trace_id
        publications.append(kwargs["routing_key"])

    def ack(**kwargs):
        assert get_ident() == owner
        assert publications == ["analysis.progress", "analysis.results"]
        publications.append("ack")

    def io_events(time_limit):
        nonlocal initial_beat
        assert get_ident() == owner
        if initial_beat is None:
            initial_beat = runtime.health_state["last_beat"]
            callback = channel.basic_consume.call_args.kwargs["on_message_callback"]
            callback(channel, MagicMock(delivery_tag=7, redelivered=False),
                     MagicMock(headers={"traceparent": f"00-{upstream_trace_id}-b7ad6b7169203331-01"}),
                     json.dumps({"analysis_id": "thread-test", "correlation_id": "thread-test"}))
        if started.is_set() and runtime.health_state["last_beat"] > initial_beat:
            assert runtime.health_state["ok"] is True
            release.set()
        try:
            callbacks.get(timeout=0.01)()
        except Empty:
            pass
        if completed.is_set():
            raise StopConsumer()

    runtime.process_task = process
    runtime.record_outcome.side_effect = lambda *args: completed.set()
    channel.basic_publish.side_effect = publish
    channel.basic_ack.side_effect = ack
    connection.process_data_events.side_effect = io_events
    with pytest.raises(StopConsumer):
        runtime.run_consumer()
    assert len(inference_threads) == 1 and inference_threads[0] != owner
    assert publications == ["analysis.progress", "analysis.results", "ack"]
    assert runtime.health_state["ok"] is False
    assert structlog.contextvars.get_contextvars() == {}


def test_disconnect_unblocks_pending_rpc_and_rejects_late_operations():
    connection, channel = MagicMock(), MagicMock()
    callbacks = Queue()
    connection.add_callback_threadsafe.side_effect = callbacks.put
    transport = ConnectionChannel(connection, channel)
    with ThreadPoolExecutor(max_workers=1) as executor:
        publication = executor.submit(transport.basic_publish, body=b"result")
        queued_callback = callbacks.get(timeout=3)
        transport.close()
        with pytest.raises(pika.exceptions.StreamLostError):
            publication.result(timeout=3)
        queued_callback()
        with pytest.raises(pika.exceptions.StreamLostError):
            transport.basic_ack(delivery_tag=7)
    channel.basic_publish.assert_not_called()
    channel.basic_ack.assert_not_called()
    assert callbacks.empty()


@pytest.mark.parametrize("error", [pika.exceptions.NackError([]),
                                  pika.exceptions.UnroutableError([]),
                                  pika.exceptions.StreamLostError("lost during ACK")])
def test_channel_error_returns_to_worker_without_hanging(error):
    connection, channel = MagicMock(), MagicMock()
    callbacks = Queue()
    connection.add_callback_threadsafe.side_effect = callbacks.put
    channel.basic_publish.side_effect = error
    transport = ConnectionChannel(connection, channel)
    with ThreadPoolExecutor(max_workers=1) as executor:
        publication = executor.submit(transport.basic_publish, body=b"result")
        callbacks.get(timeout=3)()
        with pytest.raises(type(error)):
            publication.result(timeout=3)


def test_disconnect_drains_old_attempt_before_reconnect_without_result_or_ack(runtime, monkeypatch):
    started, release, cleaned = Event(), Event(), Event()
    connection = MagicMock()
    channel = connection.channel.return_value
    monkeypatch.setattr(runtime_module.pika, "BlockingConnection", lambda _: connection)
    delivered = False

    def process(msg, progress_callback):
        started.set()
        assert release.wait(timeout=3)
        try:
            progress_callback(50, "INFERENCE")
        finally:
            cleaned.set()

    def io_events(time_limit):
        nonlocal delivered
        if not delivered:
            delivered = True
            channel.basic_consume.call_args.kwargs["on_message_callback"](
                channel, MagicMock(delivery_tag=7, redelivered=False), MagicMock(headers={}),
                json.dumps({"analysis_id": "old-attempt"}))
        assert started.wait(timeout=3)
        raise pika.exceptions.StreamLostError("disconnected during inference")

    def close():
        assert runtime.health_state["ok"] is False
        release.set()

    def backoff(seconds):
        assert seconds == 5
        assert cleaned.is_set()
        channel.basic_publish.assert_not_called()
        channel.basic_ack.assert_not_called()
        runtime.record_outcome.assert_not_called()
        raise StopConsumer()

    runtime.process_task = process
    connection.process_data_events.side_effect = io_events
    connection.close.side_effect = close
    monkeypatch.setattr(runtime_module.time, "sleep", backoff)
    with pytest.raises(StopConsumer):
        runtime.run_consumer()
