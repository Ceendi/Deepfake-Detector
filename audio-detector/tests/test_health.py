"""Readiness requires loaded models and a live consumer, without loading checkpoints."""
import asyncio
import json
import os
import subprocess
import sys
import time
import types
from pathlib import Path
from unittest.mock import MagicMock

import pika
import pytest
from detector_runtime import consumer as runtime_module

_fake_inference = types.ModuleType("src.inference")
_fake_inference.AudioInference = MagicMock()
sys.modules.setdefault("src.inference", _fake_inference)

from src import consumer, main


@pytest.fixture(autouse=True)
def ready_model(monkeypatch):
    monkeypatch.setattr(consumer, "audio_inference", MagicMock())
    monkeypatch.setattr(main, "_consumer_alive", {"ok": False, "last_beat": 0.0})


def test_up_with_fresh_heartbeat():
    main._consumer_alive.update(ok=True, last_beat=time.time())
    assert main.health() == {"status": "UP"}


def test_down_after_prolonged_silence_despite_ok_flag():
    main._consumer_alive.update(
        ok=True, last_beat=time.time() - main.HEALTH_MAX_SILENCE_SECONDS - 1)
    assert main.health().status_code == 503


def test_down_when_consumer_not_connected():
    main._consumer_alive.update(ok=False, last_beat=time.time())
    assert main.health().status_code == 503


def test_live_consumer_without_model_is_not_ready(monkeypatch):
    monkeypatch.setattr(consumer, "audio_inference", None)
    main._consumer_alive.update(ok=True, last_beat=time.time())
    assert main.health().status_code == 503


def test_model_load_failure_consumes_and_fails_while_api_stays_down():
    # A fresh interpreter exercises the production import/constructor and lifespan path
    # without re-registering Prometheus collectors or importing any model dependency.
    script = r'''import asyncio
import json
import sys
import types
from unittest.mock import MagicMock

module = types.ModuleType("src.inference")
model = MagicMock(side_effect=RuntimeError("required checkpoint is invalid"))
module.AudioInference = model
sys.modules["src.inference"] = module
from src import consumer, main
from detector_runtime import consumer as runtime_module

connection = MagicMock()
channel = connection.channel.return_value
runtime_module.pika.BlockingConnection = MagicMock(return_value=connection)
consumer.runtime.redis_client = MagicMock()
consumer.runtime.redis_client.exists.return_value = 0
consumer.runtime.s3_client = MagicMock()
published = []
def publish(**kwargs):
    channel.basic_ack.assert_not_called()
    published.append(json.loads(kwargs["body"]))
channel.basic_publish.side_effect = publish

class StopConsumer(BaseException):
    pass

def deliver(*args, **kwargs):
    assert main._consumer_alive["ok"] is True
    assert main.health().status_code == 503
    consumer._handle_message(channel, MagicMock(delivery_tag=7), MagicMock(headers={}),
        json.dumps({"analysis_id": "missing-model", "correlation_id": "ctor-failure",
                    "file_bucket": "test", "file_key": "input"}).encode())
    raise StopConsumer()

connection.process_data_events.side_effect = deliver
class InlineThread:
    def __init__(self, target, args, daemon):
        self.target, self.args = target, args
    def start(self):
        try:
            self.target(*self.args)
        except StopConsumer:
            pass

main.threading.Thread = InlineThread
async def start():
    async with main.lifespan(main.app):
        assert main.health().status_code == 503
        assert main._consumer_alive["ok"] is False
        assert main._consumer_alive["last_beat"] > 0

asyncio.run(start())
model.assert_called_once_with()
assert consumer.audio_inference is None
runtime_module.pika.BlockingConnection.assert_called_once()
consumer.runtime.s3_client.download_file.assert_not_called()
consumer.runtime.s3_client.upload_file.assert_not_called()
channel.basic_ack.assert_called_once_with(delivery_tag=7)
assert len(published) == 1
assert published[0]["status"] == "FAILED"
assert published[0]["result"] is None
assert published[0]["error"]["code"] == "MODEL_UNAVAILABLE"
'''
    result = subprocess.run(
        [sys.executable, "-c", script], cwd=Path(main.__file__).parents[1],
        env={**os.environ, "AWS_EC2_METADATA_DISABLED": "true"},
        capture_output=True, text=True, timeout=20, check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr


class StopConsumer(BaseException):
    """Exit the infinite production loop after observing recovered readiness."""


@pytest.mark.parametrize("loaded_model", [False, True])
def test_lifespan_retains_broker_recovery(monkeypatch, loaded_model):
    if not loaded_model:
        monkeypatch.setattr(consumer, "audio_inference", None)
    connection = MagicMock()
    channel = connection.channel.return_value
    connecting = MagicMock(side_effect=[pika.exceptions.AMQPConnectionError(), connection])
    monkeypatch.setattr(runtime_module.pika, "BlockingConnection", connecting)
    disconnected = []

    def backoff(seconds):
        disconnected.append(main.health().status_code)
        assert main._consumer_alive["ok"] is False
        assert seconds == 5

    monkeypatch.setattr(runtime_module.time, "sleep", backoff)

    def observe_ready(*args, **kwargs):
        if loaded_model:
            assert main.health() == {"status": "UP"}
        else:
            assert main.health().status_code == 503
        assert main._consumer_alive["last_beat"] > 0
        raise StopConsumer()

    connection.process_data_events.side_effect = observe_ready

    class InlineThread:
        def __init__(self, target, args, daemon):
            self.target, self.args = target, args

        def start(self):
            try:
                self.target(*self.args)
            except StopConsumer:
                pass

    monkeypatch.setattr(main.threading, "Thread", InlineThread)

    async def start():
        async with main.lifespan(main.app):
            assert main.health().status_code == 503

    asyncio.run(start())
    assert disconnected == [503]
    assert connecting.call_count == 2
    channel.basic_qos.assert_called_once_with(prefetch_count=1)
    channel.confirm_delivery.assert_called_once_with()


@pytest.mark.parametrize("failure", [None, "publish", "ack", "cancelled"])
def test_missing_model_task_fails_before_io_and_retains_transport_guards(monkeypatch, failure):
    monkeypatch.setattr(consumer, "audio_inference", None)
    s3 = MagicMock()
    monkeypatch.setattr(consumer.runtime, "s3_client", s3)
    redis = MagicMock()
    redis.exists.return_value = int(failure == "cancelled")
    monkeypatch.setattr(consumer.runtime, "redis_client", redis)
    channel = MagicMock()
    payloads = []

    def publish(**kwargs):
        channel.basic_ack.assert_not_called()
        assert kwargs["routing_key"] == "analysis.results"
        assert kwargs["mandatory"] is True
        assert kwargs["properties"].delivery_mode == 2
        payloads.append(json.loads(kwargs["body"]))
        if failure == "publish":
            raise pika.exceptions.NackError([])

    channel.basic_publish.side_effect = publish
    if failure == "ack":
        channel.basic_ack.side_effect = pika.exceptions.StreamLostError("lost before ack")
    args = (channel, MagicMock(delivery_tag=7), MagicMock(headers={}),
            json.dumps({"analysis_id": "missing-model", "file_bucket": "test",
                        "file_key": "input"}).encode())
    if failure == "publish":
        with pytest.raises(pika.exceptions.NackError):
            consumer._handle_message(*args)
        channel.basic_ack.assert_not_called()
    elif failure == "ack":
        with pytest.raises(pika.exceptions.StreamLostError):
            consumer._handle_message(*args)
        channel.basic_ack.assert_called_once_with(delivery_tag=7)
    else:
        consumer._handle_message(*args)
        channel.basic_ack.assert_called_once_with(delivery_tag=7)
    if failure == "cancelled":
        assert payloads == []
    else:
        assert payloads == [{"analysis_id": "missing-model", "correlation_id": "",
                             "source": "audio", "status": "FAILED", "result": None,
                             "error": {"code": "MODEL_UNAVAILABLE",
                                       "message": "Usługa analizy jest chwilowo niedostępna. "
                                                  "Spróbuj ponownie później."}}]
    s3.download_file.assert_not_called()
    s3.upload_file.assert_not_called()
