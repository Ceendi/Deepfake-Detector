# Detector readiness

Audio and video workers keep their FastAPI API and RabbitMQ consumer alive if
required model initialization fails. The failure is logged and `/health` returns
HTTP 503 (`DOWN`), even with a connected consumer and a fresh heartbeat. Tasks
received by this worker immediately publish `FAILED`, `result: null` and error code
`MODEL_UNAVAILABLE`, before any S3 download, progress event, inference or artifact
work. Confirmed result publication precedes ACK; transport failures retain the
existing reconnect/redelivery behavior, and cancellation retains its existing guards.

`/health` returns `UP` only when the inference object was successfully initialized
and the existing consumer connection and heartbeat checks pass. A broker outage
marks the worker `DOWN` and uses the existing five-second reconnect loop. With
loaded models, readiness returns when the broker recovers. Without models, broker
recovery restores task delivery and fast failure while readiness remains `DOWN`.

Model loading is attempted once during process initialization. Repair the missing or
invalid required model configuration and restart the affected worker to retry loading.
Docker health checks mark a running container unhealthy; Compose restart policies
do not restart a container solely because it is unhealthy. This patch does not add
an automatic model reload or restart mechanism.

The lightweight detector health suites stub model constructors and exercise real
API lifespan, connected-consumer readiness, fast task failure, publication/ACK guards
and broker recovery. Real worker process tests also cover unavailable-model delivery
through RabbitMQ without input or artifacts. No production model downloads are needed.
