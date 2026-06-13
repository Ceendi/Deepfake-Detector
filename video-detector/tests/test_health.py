"""/health: connected flag alone is not enough — silence past the threshold means a wedged
consumer thread, so the container must go unhealthy (D6 healthcheck)."""
import sys
import time
import types
from unittest.mock import MagicMock

_fake_inference = types.ModuleType("src.inference")
_fake_inference.VideoInference = MagicMock()
sys.modules.setdefault("src.inference", _fake_inference)

import src.main as main  # noqa: E402


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
