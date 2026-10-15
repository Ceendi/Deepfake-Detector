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
locks. This package has no independent lock. Freezing the manifests changes only
local-package metadata in the detector locks; resolved external versions and
artifact hashes remain unchanged.

`detector_runtime.metrics.inference_duration_buckets()` supplies both detectors'
duration histograms. It reuses the worker's validated task limit without changing
that deadline; see [the metrics contract](../docs/contracts/metrics.md).
