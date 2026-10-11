# Detector readiness

Audio and video workers keep their FastAPI API alive if required model initialization
fails. The failure is logged, `/health` returns HTTP 503 (`DOWN`), and the consumer
returns before opening a RabbitMQ connection. Queued tasks remain available for a
worker with successfully initialized models; an unusable worker does not claim them.

`/health` returns `UP` only when the inference object was successfully initialized
and the existing consumer connection and heartbeat checks pass. With loaded models,
a broker outage still marks the worker `DOWN` and uses the existing five-second
reconnect loop; readiness returns when the broker connection recovers.

Model loading is attempted once during process initialization. Repair the missing or
invalid required model configuration and restart the affected worker to retry loading.
Docker health checks mark a running container unhealthy; Compose restart policies
do not restart a container solely because it is unhealthy. This patch does not add
an automatic model reload or restart mechanism.

The lightweight detector health suites stub model constructors and exercise the real
API lifespan, consumer startup guard, readiness function and broker reconnect path.
They require no production checkpoints or model downloads.
