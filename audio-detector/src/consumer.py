import os

import structlog
from detector_runtime import Artifact, DetectorRuntime, create_redis_client, create_s3_client
from opentelemetry import trace
from prometheus_client import Counter

from .tracing import init_tracing

QUEUE = os.getenv("QUEUE_NAME", "analysis.audio")
SOURCE = os.getenv("SOURCE_LABEL", "audio")
log = structlog.get_logger(__name__)
init_tracing(f"{SOURCE}-detector")
tracer = trace.get_tracer(__name__)

PROCESSED_AUDIO_TOTAL = Counter(
    "audio_processed_total",
    "Total number of audio files processed",
    ["status"]
)

try:
    # Import inside the guard: a broken ML stack yields MODEL_UNAVAILABLE, not a crash loop.
    from .inference import AudioInference

    audio_inference = AudioInference()
except Exception as e:
    log.exception("failed_to_load_audio_inference", error=str(e))
    audio_inference = None

def _analyze(model, msg, input_path, progress_callback):
    return model.analyze(input_path, mode=msg.get("mode", "accurate"),
                         progress_callback=progress_callback)


def _artifacts(msg, result, attempt_id):
    if "local_gradcam_path" in result and os.path.exists(result["local_gradcam_path"]):
        path = result["local_gradcam_path"]
        return [Artifact(path, f"{msg['analysis_id']}/{SOURCE}/gradcam_{attempt_id}.png",
                         cleanup_field="local_gradcam_path", missing_ok=False)]
    return []


def _record_outcome(outcome, result):
    status = {"completed": "success", "failed": "error", "cancelled": "cancelled"}[outcome]
    PROCESSED_AUDIO_TOTAL.labels(status=status).inc()


def process(msg: dict, progress_callback=None) -> dict:
    return runtime.process(
        msg, model=audio_inference, analyze=_analyze, artifacts=_artifacts,
        progress_callback=progress_callback, download_fields={"mode": msg.get("mode", "accurate")},
    )


runtime = DetectorRuntime(
    source=SOURCE, queue=QUEUE, log=log, tracer=tracer,
    s3_client=create_s3_client("http://localhost:9000"),
    redis_client=create_redis_client(),
    process_task=lambda msg, progress_callback: process(msg, progress_callback=progress_callback),
    record_outcome=_record_outcome,
    progress_interval_seconds=1,
)

_handle_message = runtime.handle_message
run_consumer = runtime.run_consumer
