import json
import subprocess
import sys
from pathlib import Path

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
