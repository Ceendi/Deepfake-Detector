import json
import os
import time

import boto3
import pika
import redis
import structlog
from botocore.client import Config
from prometheus_client import Counter, Histogram

RABBIT_HOST = os.getenv("RABBITMQ_HOST", "rabbitmq")
RABBIT_USER = os.getenv("RABBITMQ_USER", "deepfake")
RABBIT_PASS = os.getenv("RABBITMQ_PASSWORD", "changeme_dev")
QUEUE       = os.getenv("QUEUE_NAME", "analysis.video")
SOURCE      = os.getenv("SOURCE_LABEL", "video")   # "video" or "audio"
EXCHANGE    = "analysis.exchange"
DLX         = "analysis.dlx"
ARTIFACTS_BUCKET = "analysis-artifacts"
DEDUP_TTL_SECONDS = int(os.getenv("DEDUP_TTL_SECONDS", "3600"))

log = structlog.get_logger(__name__)

INFERENCE_LATENCY = Histogram(
    "inference_latency_seconds",
    "Wall-clock time of one process() inference",
    ["source"],
)
DETECTOR_RESULTS = Counter(
    "detector_results_total",
    "Detector outcomes",
    ["source", "outcome"],  # outcome: fake | real | failed
)

s3_client = boto3.client(
    "s3",
    endpoint_url=os.environ.get("S3_ENDPOINT", "http://localhost:8333"),
    aws_access_key_id=os.environ.get("S3_ACCESS_KEY", ""),
    aws_secret_access_key=os.environ.get("S3_SECRET_KEY", ""),
    region_name=os.environ.get("S3_REGION", "us-east-1"),
    config=Config(s3={"addressing_style": "path"}),  # SeaweedFS requires path-style addressing
)
redis_client = redis.Redis(
    host=os.environ.get("REDIS_HOST", "localhost"),
    port=int(os.environ.get("REDIS_PORT", "6379")),
    password=os.environ.get("REDIS_PASSWORD"),
    db=0,
    decode_responses=True,
)

try:
    from .inference import VideoInference

    video_inference = VideoInference()
except Exception as e:
    # Brak modeli (np. niewyeksportowany ONNX) nie moze polozyc /health calego serwisu;
    # kazde zadanie konczy sie wtedy publikacja FAILED z czytelnym bledem.
    log.error("failed_to_load_video_inference", error=str(e))
    video_inference = None


def process(msg: dict, progress_callback=None) -> dict:
    """Pelny pipeline jednego zadania: start-ping -> S3 download -> inferencja ->
    upload heatmap -> wynik zgodny z docs/contracts/amqp-messages.md."""
    if not video_inference:
        raise RuntimeError("VideoInference module not initialized properly.")
    analysis_id = msg["analysis_id"]
    # Start-ping PRZED downloadem: Orchestrator flipuje PENDING->PROCESSING w momencie
    # podjecia zadania, a kazdy progress jest heartbeatem dla stuck-job recovery.
    if progress_callback:
        progress_callback(0, "LOADING")
    input_path = f"/tmp/{analysis_id}_input"
    log.info("downloading_file", bucket=msg["file_bucket"], key=msg["file_key"])
    s3_client.download_file(msg["file_bucket"], msg["file_key"], input_path)
    try:
        result = video_inference.analyze(input_path, progress_callback=progress_callback)
    finally:
        if os.path.exists(input_path):
            os.remove(input_path)

    # Kontrakt: gole klucze obiektow {analysisId}/{source}/{name}.png (bez schematu URI,
    # bez bucketa) — autoryzowany URL buduje Orchestrator. Upload fail-soft: heatmapy
    # sa pomocnicze, wynik analizy publikujemy nawet bez nich.
    gradcam_keys = []
    for local_path, frame_idx in result.pop("local_gradcam_paths", []):
        key = f"{analysis_id}/{SOURCE}/gradcam_frame_{frame_idx:02d}.png"
        try:
            s3_client.upload_file(local_path, ARTIFACTS_BUCKET, key,
                                  ExtraArgs={"ContentType": "image/png"})
            gradcam_keys.append(key)
        except Exception as e:
            log.error("gradcam_upload_failed", error=str(e), key=key)
        finally:
            if os.path.exists(local_path):
                os.remove(local_path)
    result["gradcam_keys"] = gradcam_keys
    return result


def _publish(ch, routing_key: str, payload: dict) -> None:
    ch.basic_publish(
        exchange=EXCHANGE,
        routing_key=routing_key,
        body=json.dumps(payload).encode(),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
        mandatory=True,  # with confirm_delivery: raises UnroutableError if no queue is bound to routing_key
    )


def _try_acquire_dedup(analysis_id: str) -> bool:
    """SETNX processing:{analysis_id}:{source} — per-source (FULL = 2 niezalezne wyniki,
    wspolny klucz wygasilby drugi detektor). Fail-open: Redis to akcelerator, autorytetem
    poprawnosci jest guard terminalny w DB Orchestratora."""
    try:
        return redis_client.set(
            f"processing:{analysis_id}:{SOURCE}", "1", nx=True, ex=DEDUP_TTL_SECONDS,
        ) is not None
    except redis.RedisError as e:
        log.warning("redis_dedup_unavailable_failing_open", error=str(e))
        return True


def _release_dedup(analysis_id: str) -> None:
    """Po FAILED zwalniamy klucz, zeby ewentualna ponowna proba mogla przetworzyc zadanie
    (guard DB i tak odrzuci spozniony wynik). Po sukcesie klucz zostaje do TTL."""
    try:
        redis_client.delete(f"processing:{analysis_id}:{SOURCE}")
    except redis.RedisError as e:
        log.warning("redis_dedup_release_failed", error=str(e))


def _handle_message(ch, method, properties, body):
    try:
        msg = json.loads(body)
        analysis_id = msg["analysis_id"]
        correlation_id = msg.get("correlation_id", "")
    except (json.JSONDecodeError, KeyError, TypeError) as e:
        log.error("bad_message_to_dlq", error=str(e), error_type=type(e).__name__)
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
        return

    structlog.contextvars.bind_contextvars(
        analysis_id=analysis_id,
        correlation_id=correlation_id,
        source=SOURCE,
    )
    try:
        log.info("processing_started")
        if not _try_acquire_dedup(analysis_id):
            log.warning("duplicate_message_dropped", reason="already_processing")
            ch.basic_ack(delivery_tag=method.delivery_tag)
            return

        def progress_callback(pct: int, stage: str = "INFERENCE", details: dict = None):
            payload = {
                "analysis_id": analysis_id,
                "correlation_id": correlation_id,
                "source": SOURCE,
                "progress": pct,
                "stage": stage,
            }
            if details is not None:
                payload["details"] = details
            _publish(ch, "analysis.progress", payload)

        with INFERENCE_LATENCY.labels(SOURCE).time():
            result = process(msg, progress_callback=progress_callback)
        DETECTOR_RESULTS.labels(SOURCE, result["verdict"].lower()).inc()
        _publish(ch, "analysis.results", {
            "analysis_id": analysis_id,
            "correlation_id": correlation_id,
            "source": SOURCE,
            "status": "COMPLETED",
            "result": result,
            "error": None,
        })
        ch.basic_ack(delivery_tag=method.delivery_tag)
        log.info("processing_completed", verdict=result["verdict"], prob_fake=result["prob_fake"])
    except Exception as e:
        log.exception("processing_failed")
        DETECTOR_RESULTS.labels(SOURCE, "failed").inc()
        _release_dedup(analysis_id)
        _publish(ch, "analysis.results", {
            "analysis_id": analysis_id,
            "correlation_id": correlation_id,
            "source": SOURCE,
            "status": "FAILED",
            "result": None,
            # InferenceError niesie kod kontraktowy (NO_FACE_DETECTED itd.);
            # kazdy inny wyjatek spada do generycznego PROCESSING_ERROR
            "error": {"code": getattr(e, "code", "PROCESSING_ERROR"), "message": str(e)},
        })
        # ack — failure info already published; we do not want infinite redelivery
        ch.basic_ack(delivery_tag=method.delivery_tag)
    finally:
        structlog.contextvars.clear_contextvars()


def run_consumer(health_state: dict) -> None:
    while True:
        try:
            creds = pika.PlainCredentials(RABBIT_USER, RABBIT_PASS)
            params = pika.ConnectionParameters(
                host=RABBIT_HOST, credentials=creds,
                heartbeat=30, blocked_connection_timeout=300,
            )
            conn = pika.BlockingConnection(params)
            ch = conn.channel()
            ch.basic_qos(prefetch_count=1)
            ch.confirm_delivery()  # publisher confirms (CLAUDE.md D6)

            # Topology declaration — broker boots empty, every publisher/consumer
            # must declare its own exchanges/queues on startup. Arguments MUST
            # match RabbitConfig.java in the Orchestrator, otherwise PRECONDITION_FAILED.
            # Single source of truth: docs/contracts/amqp-messages.md.
            ch.exchange_declare(exchange=EXCHANGE, exchange_type="topic",  durable=True)
            ch.exchange_declare(exchange=DLX,      exchange_type="direct", durable=True)
            ch.queue_declare(queue=QUEUE, durable=True, arguments={
                "x-dead-letter-exchange":    DLX,
                "x-dead-letter-routing-key": f"{QUEUE}.dlq",
            })
            ch.queue_declare(queue=f"{QUEUE}.dlq", durable=True)
            ch.queue_declare(queue="analysis.results",  durable=True)
            ch.queue_declare(queue="analysis.progress", durable=True)
            ch.queue_bind(queue=QUEUE,               exchange=EXCHANGE, routing_key=QUEUE)
            ch.queue_bind(queue=f"{QUEUE}.dlq",      exchange=DLX,      routing_key=f"{QUEUE}.dlq")
            ch.queue_bind(queue="analysis.results",  exchange=EXCHANGE, routing_key="analysis.results")
            ch.queue_bind(queue="analysis.progress", exchange=EXCHANGE, routing_key="analysis.progress")

            health_state["ok"] = True
            health_state["last_beat"] = time.time()
            log.info("consumer_ready", queue=QUEUE, source=SOURCE)

            ch.basic_consume(queue=QUEUE, on_message_callback=_handle_message, auto_ack=False)
            # Petla z process_data_events zamiast start_consuming: heartbeat /health bije
            # takze przy pustej kolejce (cisza > prog = zaklinowany watek -> 503, D6).
            while True:
                conn.process_data_events(time_limit=5)
                health_state["last_beat"] = time.time()
        except Exception as e:
            health_state["ok"] = False
            log.error("consumer_crashed_reconnecting", error=str(e), backoff_seconds=5)
            time.sleep(5)
