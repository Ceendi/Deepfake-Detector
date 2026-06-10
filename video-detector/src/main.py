import logging
import threading
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from prometheus_client import make_asgi_app

from .consumer import run_consumer

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

_consumer_alive = {"ok": False}


@asynccontextmanager
async def lifespan(_: FastAPI):
    threading.Thread(target=run_consumer, args=(_consumer_alive,), daemon=True).start()
    yield


app = FastAPI(lifespan=lifespan)

# Default process/python_gc metrics for Prometheus scrape (PR4). TODO(Osoba 3/4): add business
# metrics (inference_latency_seconds, per-stage counters) in the pipeline.
app.mount("/metrics", make_asgi_app())


@app.get("/health")
def health():
    if _consumer_alive["ok"]:
        return {"status": "UP"}
    return JSONResponse(status_code=503, content={"status": "DOWN"})
