import logging
import threading
import time
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from prometheus_client import make_asgi_app

from . import consumer
from .config import load_config

# every log line is JSON + correlation_id + trace_id (Loki + OpenTelemetry).
# contextvars.merge_contextvars pulls in bind_contextvars(...) set by the consumer handler.
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

# The I/O loop keeps beating during inference; silence means the connection thread is stuck.
HEALTH_MAX_SILENCE_SECONDS = load_config()["HEALTH_MAX_SILENCE_SECONDS"]

_consumer_alive = consumer.runtime.health_state


@asynccontextmanager
async def lifespan(_: FastAPI):
    threading.Thread(target=consumer.run_consumer, args=(_consumer_alive,), daemon=True).start()
    yield


app = FastAPI(lifespan=lifespan)

app.mount("/metrics", make_asgi_app())


@app.get("/health")
def health():
    # A connected flag alone cannot detect a stalled connection thread.
    silence = time.time() - _consumer_alive.get("last_beat", 0.0)
    if (consumer.video_inference is not None and _consumer_alive["ok"]
            and silence <= HEALTH_MAX_SILENCE_SECONDS):
        return {"status": "UP"}
    return JSONResponse(status_code=503, content={"status": "DOWN"})
