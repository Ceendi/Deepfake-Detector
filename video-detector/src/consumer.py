import os

import structlog
from detector_runtime import Artifact, DetectorRuntime, create_redis_client, create_s3_client
from opentelemetry import trace
from prometheus_client import Counter, Histogram

from .tracing import init_tracing

QUEUE = os.getenv("QUEUE_NAME", "analysis.video")
SOURCE = os.getenv("SOURCE_LABEL", "video")
log = structlog.get_logger(__name__)
init_tracing(f"{SOURCE}-detector")
tracer = trace.get_tracer(__name__)

INFERENCE_LATENCY = Histogram(
    "inference_latency_seconds",
    "Wall-clock time of one process() inference",
    ["source"],
)
DETECTOR_RESULTS = Counter(
    "detector_results_total",
    "Detector outcomes",
    ["source", "outcome"],  # outcome: fake | real | failed | cancelled
)

try:
    from .inference import VideoInference

    video_inference = VideoInference()
except Exception as e:
    log.exception("failed_to_load_video_inference", error=str(e))
    video_inference = None

def _analyze(model, msg, input_path, progress_callback):
    return model.analyze(input_path, progress_callback=progress_callback,
                         workdir=os.path.dirname(input_path))


def _artifacts(msg, result, attempt_id):
    for path, frame_idx in result.pop("local_gradcam_paths", []):
        key = f"{msg['analysis_id']}/{SOURCE}/gradcam_frame_{frame_idx:02d}_{attempt_id}.png"
        yield Artifact(path, key, log_fields={"key": key})


def _record_outcome(outcome, result):
    label = result["verdict"].lower() if outcome == "completed" else outcome
    DETECTOR_RESULTS.labels(SOURCE, label).inc()


def process(msg: dict, progress_callback=None) -> dict:
    return runtime.process(
        msg, model=video_inference,
        unavailable_message="Usługa analizy jest chwilowo niedostępna. Spróbuj ponownie później.",
        analyze=_analyze, artifacts=_artifacts, progress_callback=progress_callback,
    )


runtime = DetectorRuntime(
    source=SOURCE, queue=QUEUE, log=log, tracer=tracer,
    s3_client=create_s3_client("http://localhost:8333", "", ""),
    redis_client=create_redis_client(),
    process_task=lambda msg, progress_callback: process(msg, progress_callback=progress_callback),
    record_outcome=_record_outcome,
    inference_timer=lambda: INFERENCE_LATENCY.labels(SOURCE).time(),
)

_handle_message = runtime.handle_message
run_consumer = runtime.run_consumer
