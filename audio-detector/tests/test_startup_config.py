"""Invalid settings must stop startup before importing any model dependencies."""

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest


@pytest.mark.parametrize(
    "name,value",
    [
        ("DETECTOR_TASK_HARD_LIMIT_SECONDS", "private-setting-value"),
        ("RABBITMQ_PASSWORD", ""),
        ("S3_SECRET_KEY", ""),
        ("RABBITMQ_PORT", "0"),
        ("REDIS_PORT", "65536"),
        ("ONNX_INTRA_THREADS", "-1"),
        ("ONNX_INTRA_THREADS", "private-setting-value"),
        ("MEDIA_MAX_DURATION_SECONDS", "nan"),
        ("MEDIA_MAX_DURATION_SECONDS", "0"),
        ("MEL_EER_THRESHOLD", "0"),
        ("W2V2_EER_THRESHOLD", "1"),
        ("MEL_EER_THRESHOLD", "nan"),
    ],
)
def test_invalid_configuration_stops_before_model_import(name, value):
    script = """import sys, types
from unittest.mock import MagicMock
model = types.ModuleType('src.inference')
def loaded():
    print('MODEL_WAS_LOADED')
    return MagicMock()
model.MODEL_CLASS = loaded
sys.modules['src.inference'] = model
from src import consumer
print('STARTUP_COMPLETED')
""".replace("MODEL_CLASS", "AudioInference")
    env = dict(os.environ, **{name: value})
    result = subprocess.run(
        [sys.executable, "-c", script],
        env=env,
        cwd=Path(__file__).parents[1],
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    output = result.stdout + result.stderr
    assert result.returncode != 0, output
    assert name in output
    assert "MODEL_WAS_LOADED" not in output
    assert "STARTUP_COMPLETED" not in output
    assert all(isinstance(json.loads(line), dict) for line in result.stdout.splitlines())
    if value == "private-setting-value":
        assert value not in output



def test_failed_model_startup_emits_only_json():
    script = """import sys, types
from unittest.mock import MagicMock
module = types.ModuleType('src.inference')
def fail():
    import structlog
    print('third party model output')
    print('third party model warning', file=sys.stderr)
    structlog.get_logger().error('model_file_download_failed')
    raise RuntimeError('controlled failure')
module.AudioInference = fail
sys.modules['src.inference'] = module
from src import consumer
"""
    result = subprocess.run([sys.executable, "-c", script], cwd=Path(__file__).parents[1],
                            capture_output=True, text=True, timeout=20, check=False)
    assert result.returncode == 0, result.stderr
    lines = (result.stdout + result.stderr).splitlines()
    assert lines
    events = [json.loads(line) for line in lines]
    assert any(event["event"] == "failed_to_load_audio_inference" for event in events)
