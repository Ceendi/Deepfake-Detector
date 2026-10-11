"""Readiness requires loaded models and a live consumer, without loading checkpoints."""
import asyncio
import os
import subprocess
import sys
import time
import types
from pathlib import Path
from unittest.mock import MagicMock

import pika
import pytest

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


def test_model_load_failure_keeps_api_alive_without_consuming():
    # A fresh interpreter exercises the production import/constructor and lifespan path
    # without re-registering Prometheus collectors or importing any model dependency.
    script = r'''import asyncio
import sys
import types
from unittest.mock import MagicMock

module = types.ModuleType("src.inference")
model = MagicMock(side_effect=RuntimeError("required checkpoint is invalid"))
module.AudioInference = model
sys.modules["src.inference"] = module
from src import consumer, main

connection = MagicMock()
consumer.pika.BlockingConnection = connection

class InlineThread:
    def __init__(self, target, args, daemon):
        self.target, self.args = target, args
    def start(self):
        self.target(*self.args)

main.threading.Thread = InlineThread
main._consumer_alive.update(ok=True, last_beat=123.0)
async def start():
    async with main.lifespan(main.app):
        assert main.health().status_code == 503
        assert main._consumer_alive["ok"] is False
        assert main._consumer_alive["last_beat"] == 0.0

asyncio.run(start())
model.assert_called_once_with()
assert consumer.audio_inference is None
connection.assert_not_called()
'''
    result = subprocess.run(
        [sys.executable, "-c", script], cwd=Path(main.__file__).parents[1],
        env={**os.environ, "AWS_EC2_METADATA_DISABLED": "true"},
        capture_output=True, text=True, timeout=20, check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr


class StopConsumer(BaseException):
    """Exit the infinite production loop after observing recovered readiness."""


def test_lifespan_retains_broker_recovery_with_loaded_model(monkeypatch):
    connection = MagicMock()
    channel = connection.channel.return_value
    connecting = MagicMock(side_effect=[pika.exceptions.AMQPConnectionError(), connection])
    monkeypatch.setattr(consumer.pika, "BlockingConnection", connecting)
    disconnected = []

    def backoff(seconds):
        disconnected.append(main.health().status_code)
        assert main._consumer_alive["ok"] is False
        assert seconds == 5

    monkeypatch.setattr(consumer.time, "sleep", backoff)

    def observe_ready(*args, **kwargs):
        assert main.health() == {"status": "UP"}
        assert main._consumer_alive["last_beat"] > 0
        raise StopConsumer()

    channel.consume.side_effect = observe_ready

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
            assert main.health() == {"status": "UP"}

    asyncio.run(start())
    assert disconnected == [503]
    assert connecting.call_count == 2
    channel.basic_qos.assert_called_once_with(prefetch_count=1)
    channel.confirm_delivery.assert_called_once_with()
