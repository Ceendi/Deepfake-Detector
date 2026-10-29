import json
import logging
import os
import subprocess
import sys
from pathlib import Path

import pytest
import structlog

from detector_runtime import logging as json_logging


def test_uvicorn_config_emits_json_with_trace_context():
    config = Path(json_logging.__file__).with_name("uvicorn-log.json")
    script = """import json, logging, logging.config, sys
import structlog
with open(sys.argv[1]) as stream:
    logging.config.dictConfig(json.load(stream))
structlog.contextvars.bind_contextvars(correlation_id='corr-1', trace_id='trace-1')
logging.getLogger('uvicorn.error').info('Application startup complete.')
"""
    result = subprocess.run([sys.executable, "-c", script, str(config)],
                            capture_output=True, text=True, check=True, timeout=20)
    payload = json.loads(result.stderr)
    assert payload["event"] == "Application startup complete."
    assert payload["level"] == "info"
    assert payload["correlation_id"] == "corr-1"
    assert payload["trace_id"] == "trace-1"


def test_formatter_adds_bound_context_and_exception():
    structlog.contextvars.bind_contextvars(correlation_id="corr-2", trace_id="trace-2")
    try:
        try:
            raise RuntimeError("boom")
        except RuntimeError:
            record = logging.getLogger("detector").makeRecord(
                "detector", logging.ERROR, __file__, 1, "failed %s", ("task",), sys.exc_info())
        payload = json.loads(json_logging.JsonFormatter().format(record))
    finally:
        structlog.contextvars.clear_contextvars()
    assert payload["event"] == "failed task"
    assert payload["level"] == "error"
    assert payload["logger"] == "detector"
    assert payload["correlation_id"] == "corr-2"
    assert payload["trace_id"] == "trace-2"
    assert "RuntimeError: boom" in payload["exception"]
    assert payload["timestamp"].endswith("+00:00")


def test_formatter_omits_exception_without_exc_info():
    record = logging.LogRecord("detector", logging.INFO, __file__, 1, "ready", None, None)
    payload = json.loads(json_logging.JsonFormatter().format(record))
    assert payload["event"] == "ready"
    assert "exception" not in payload


@pytest.fixture
def restored_logging(monkeypatch):
    monkeypatch.delenv("HF_HUB_DISABLE_PROGRESS_BARS", raising=False)
    root = logging.getLogger()
    handlers, level = root.handlers[:], root.level
    config = structlog.get_config()
    yield
    root.handlers[:] = handlers
    root.setLevel(level)
    structlog.configure(**config)


def test_configure_installs_json_handler_and_silences_progress_bars(restored_logging):
    json_logging.configure()
    root = logging.getLogger()
    assert os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] == "1"
    assert root.level == logging.INFO
    assert [type(handler.formatter) for handler in root.handlers] == [json_logging.JsonFormatter]
    assert isinstance(structlog.get_config()["processors"][-1],
                      structlog.processors.JSONRenderer)


def test_model_output_wraps_plain_lines_and_keeps_json_records(capsys):
    with json_logging.model_output():
        print("loading weights")
        print(json.dumps({"event": "already_json"}))
        print("   ")
        sys.stderr.write("warning without newline")
    out, err = capsys.readouterr()
    assert [json.loads(line) for line in out.splitlines()] == [
        {"event": "model_output", "level": "info", "message": "loading weights"},
        {"event": "already_json"},
    ]
    assert json.loads(err) == {"event": "model_output", "level": "info",
                               "message": "warning without newline"}
