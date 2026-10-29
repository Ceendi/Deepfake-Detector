"""JSON logging must be ready before configuration checks and model imports."""

import json
import logging
import os
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from datetime import UTC, datetime

import structlog


class JsonFormatter(logging.Formatter):
    def format(self, record):
        payload = {"event": record.getMessage(), "level": record.levelname.lower(),
                   "logger": record.name,
                   "timestamp": datetime.now(UTC).isoformat()}
        payload.update(structlog.contextvars.get_contextvars())
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload)


def configure():
    os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    logging.basicConfig(handlers=[handler], level=logging.INFO, force=True)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.dict_tracebacks,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        cache_logger_on_first_use=True,
    )


class _ModelOutput:
    def __init__(self, output):
        self.output = output
        self.pending = ""

    def write(self, value):
        self.pending += value
        while "\n" in self.pending:
            line, self.pending = self.pending.split("\n", 1)
            self._emit(line)
        return len(value)

    def _emit(self, line):
        if line.strip():
            # Some model libraries print directly; preserve existing JSON records.
            try:
                json.loads(line)
                rendered = line
            except ValueError:
                rendered = json.dumps({"event": "model_output", "level": "info", "message": line})
            self.output.write(rendered + "\n")
            self.output.flush()

    def flush(self):
        if self.pending:
            self._emit(self.pending)
            self.pending = ""


@contextmanager
def model_output():
    import sys

    stdout, stderr = _ModelOutput(sys.stdout), _ModelOutput(sys.stderr)
    try:
        with redirect_stdout(stdout), redirect_stderr(stderr):
            yield
    finally:
        stdout.flush()
        stderr.flush()
