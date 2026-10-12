# Detector runtime

Shared synchronous consumer for audio and video. Detector adapters supply models,
artifact names, existing metrics and their `src.consumer` logger/tracer. The runtime
owns AMQP topology, confirms before ACK, reconnect, Redis cancellation, private
attempt directories and consumer health state. It introduces no worker thread or
new AMQP contract.

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
external dependency layer. Each detector keeps its existing consume loop and
heartbeat timing until the separate worker-thread change.
