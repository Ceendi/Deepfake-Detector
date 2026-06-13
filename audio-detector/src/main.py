import logging
import threading
import time
from contextlib import asynccontextmanager
import structlog
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from prometheus_client import make_asgi_app
from .consumer import run_consumer
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
# Consumer loop bumps last_beat at least every ~5s (consume inactivity ticks); a flag stuck
# on ok=True with no beats means a wedged thread, not a healthy consumer.
HEALTH_MAX_SILENCE_SECONDS = 60
@asynccontextmanager
async def lifespan(_: FastAPI):
    threading.Thread(target=run_consumer, args=(_consumer_alive,), daemon=True).start()
    yield
app = FastAPI(lifespan=lifespan)
metrics_app = make_asgi_app()
app.mount("/metrics", metrics_app)
@app.get("/health")
def health():
    silence = time.time() - _consumer_alive.get("last_beat", 0)
    if _consumer_alive["ok"] and silence < HEALTH_MAX_SILENCE_SECONDS:
        return {"status": "UP"}
    return JSONResponse(status_code=503, content={"status": "DOWN"})
