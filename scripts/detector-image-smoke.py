"""Offline API smoke: real ML imports, controlled model and broker readiness."""

import importlib
import os
import time
from unittest.mock import patch

import uvicorn

source = os.environ["SMOKE_SOURCE"]
inference = importlib.import_module("src.inference")


def connected(health):
    while True:
        health.update(ok=True, last_beat=time.time())
        time.sleep(0.1)


# Checkpoint loading and broker delivery have separate integration suites.
# Import the real inference first so missing native libraries cannot be hidden by the guard.
with patch.object(inference, "VideoInference" if source == "video" else "AudioInference"):
    from src import consumer, main

consumer.run_consumer = connected
uvicorn.run(main.app, host="0.0.0.0", port=8000)
