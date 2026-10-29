# Detector runtime

Shared AMQP consumer for audio and video. Detector adapters supply models,
artifact names, existing metrics and their `src.consumer` logger/tracer. The runtime
owns AMQP topology, confirms before ACK, reconnect, Redis cancellation, private
attempt directories and consumer health state. A single inference thread handles
downloads, model calls and artifact uploads while the connection thread services
pika I/O and updates health. Channel calls use `add_callback_threadsafe` and wait
for completion, so a confirmed result still precedes the task ACK. If the connection
fails, pending calls are released and late calls from that attempt are rejected.
The old attempt is drained before reconnecting to avoid concurrent model calls.
Only `DetectorError` subclasses publish their own error code and message; other
exceptions become `PROCESSING_ERROR`. Transient S3 failures redeliver the task with
a growing reconnect backoff, and a watchdog restarts a process whose task exceeds
`DETECTOR_TASK_HARD_LIMIT_SECONDS` (see `docs/reliability.md`). `RABBITMQ_PASSWORD`,
`S3_ACCESS_KEY` and `S3_SECRET_KEY` have no defaults; the test suites set dummy values.

From the repository root, install the lightweight test environment:

```bash
python -m pip install -r audio-detector/tests/requirements-light.txt
python -m pytest -q detector-runtime/tests
(cd audio-detector && python -m pytest -q tests)
(cd video-detector && python -m pytest -q tests)
tests/worker_redelivery/run.sh
```

`uv sync --dev` inside either detector installs this local package from
`../detector-runtime`. Compose supplies the same package as an additional build
context. For a direct image build from the repository root:

```bash
docker build --build-context detector_runtime=detector-runtime -t detector-audio audio-detector
docker build --build-context detector_runtime=detector-runtime -t detector-video video-detector
```

Docker installs external dependencies before copying runtime source and installing
the local package with `--no-deps`; changing runtime or detector code preserves the
external dependency layer. Both detectors use the same I/O loop, with the existing
30 s AMQP heartbeat, prefetch 1 and 5 s reconnect backoff.

## Runtime dependency versions

Both detector manifests pin external runtime dependencies to versions in their
respective `uv.lock`. Docker builds install the frozen production graph with
`uv sync --frozen --no-dev`, then install this local package with `--no-deps`.
Manifest pins also keep direct installations and later lock refreshes explicit.

This package's Redis 8.1.0 and OpenTelemetry API 1.45.1 pins match both detector
locks. Its own `uv.lock` also pins the standalone development environment, so
`uv sync --frozen --dev` and `uv run --frozen pytest tests` work in this directory.

`detector_runtime.metrics.inference_duration_buckets()` supplies both detectors'
duration histograms. It reuses the worker's validated task limit without changing
that deadline; see [the metrics contract](../docs/contracts/metrics.md).

## Startup validation

Both adapters validate settings before the optional ML import/load guard. Invalid values log
only the setting name and reason, then exit with status 1; configuration errors cannot turn
all tasks into `MODEL_UNAVAILABLE`. Missing/corrupt checkpoints and broken ML imports retain
that domain result and unhealthy readiness. Broker and storage credentials must be supplied
before model loading; no connection attempt or reconnect loop hides missing credentials.

- `MEDIA_MAX_DURATION_SECONDS`: finite and positive (default 1800).
- Video `ONNX_INTRA_THREADS`: positive integer (default 4); applies to backbone, temporal,
  InsightFace CPU sessions and torch. Audio accepts nonnegative integers (default 0 for library
  defaults; Compose still supplies 6).
- Video `MEDIA_MAX_PIXELS`, `DET_SIZE`: positive integers (defaults 8294400, 640).
- Video `MIN_FACES`, `GRADCAM_TOPK`: integers 1–16 (defaults 4, 3).
- `DECISION_THRESHOLD` (video), `MEL_EER_THRESHOLD` and `W2V2_EER_THRESHOLD` (audio): finite,
  strictly between 0 and 1; existing defaults remain 0.5, 0.3049 and 0.3000.
- Video `FRAME_STRATEGY`: `uniform` (default) or `middle2s`.
- Video `HEALTH_MAX_SILENCE_SECONDS`: positive integer (default 120).
- `RABBITMQ_PORT`, `REDIS_PORT`: integers 1–65535 (defaults 5672, 6379).

Runtime image code, dependencies and bundled models belong to root and are read-only to the
`app` user. Temporary attempts and generated heatmaps remain under `/tmp`; model downloads
must happen at build time. The image verification script attempts effective write-access
checks as `app`, including the InsightFace pack.
