import logging
import os
import threading
import time
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

# Prog ciszy heartbeatu (D6): musi byc wyrazne wiekszy niz najdluzsza pojedyncza
# inferencja — w trakcie process() petla consumera nie bije (BlockingConnection
# jest jednowatkowe), wiec zbyt niski prog flapowalby healthcheckiem pod obciazeniem.
HEALTH_MAX_SILENCE_SECONDS = int(os.getenv("HEALTH_MAX_SILENCE_SECONDS", "120"))

_consumer_alive = {"ok": False, "last_beat": 0.0}


@asynccontextmanager
async def lifespan(_: FastAPI):
    threading.Thread(target=run_consumer, args=(_consumer_alive,), daemon=True).start()
    yield


app = FastAPI(lifespan=lifespan)

app.mount("/metrics", make_asgi_app())


@app.get("/health")
def health():
    # Sama flaga "connected" nie wystarcza: zaklinowany watek consumera dalej trzyma
    # polaczenie — dopiero cisza heartbeatu powyzej progu oznacza serwis DOWN.
    silence = time.time() - _consumer_alive.get("last_beat", 0.0)
    if _consumer_alive["ok"] and silence <= HEALTH_MAX_SILENCE_SECONDS:
        return {"status": "UP"}
    return JSONResponse(status_code=503, content={"status": "DOWN"})
