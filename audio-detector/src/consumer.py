import json
import os
import tempfile
import time
import uuid

import boto3
import pika
import redis
import structlog
from botocore.client import Config
from opentelemetry import trace
from opentelemetry.trace import SpanKind, StatusCode
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator
from prometheus_client import Counter
from redis.backoff import NoBackoff
from redis.retry import Retry

from .inference import AudioInference
from .tracing import init_tracing

PROCESSED_AUDIO_TOTAL = Counter(
    "audio_processed_total",
    "Total number of audio files processed",
    ["status"]
)
RABBIT_HOST = os.getenv("RABBITMQ_HOST", "rabbitmq")
RABBIT_USER = os.getenv("RABBITMQ_USER", "deepfake")
RABBIT_PASS = os.getenv("RABBITMQ_PASSWORD", "changeme_dev")
QUEUE       = os.getenv("QUEUE_NAME", "analysis.audio")
SOURCE      = os.getenv("SOURCE_LABEL", "audio")                       
EXCHANGE    = "analysis.exchange"
DLX         = "analysis.dlx"
log = structlog.get_logger(__name__)
init_tracing(f"{SOURCE}-detector")
tracer = trace.get_tracer(__name__)
_propagator = TraceContextTextMapPropagator()  # W3C traceparent, same as the Java services
s3_client = boto3.client(
    "s3",
    endpoint_url=os.environ.get("S3_ENDPOINT", "http://localhost:9000"),
    aws_access_key_id=os.environ.get("S3_ACCESS_KEY", "minioadmin"),
    aws_secret_access_key=os.environ.get("S3_SECRET_KEY", "minioadmin"),
    region_name=os.environ.get("S3_REGION", "us-east-1"),
    config=Config(s3={"addressing_style": "path"}),
)
redis_client = redis.Redis(
    host=os.environ.get("REDIS_HOST", "localhost"),
    port=int(os.environ.get("REDIS_PORT", "6379")),
    password=os.environ.get("REDIS_PASSWORD"),
    socket_connect_timeout=1,
    socket_timeout=1,
    retry=Retry(NoBackoff(), 0),
    db=0,
    decode_responses=True
)
try:
    audio_inference = AudioInference()
except Exception as e:
    log.exception("failed_to_load_audio_inference", error=str(e))
    audio_inference = None


class AnalysisCancelled(Exception):
    """Task aborted because the user cancelled the analysis (cooperative cancel)."""


def _is_cancelled(analysis_id: str) -> bool:
    # Cooperative cancel: the Orchestrator flags cancel:{analysis_id} in Redis after a committed
    # DELETE (amqp-messages.md). Fail-open — Redis down just means we finish the work and the
    # late result bounces off the Orchestrator's terminal-state guard.
    try:
        return redis_client.exists(f"cancel:{analysis_id}") > 0
    except redis.RedisError:
        return False


def process(msg: dict, progress_callback=None) -> dict:
    # Duplicate deliveries can run concurrently, including in processes sharing /tmp.
    # Keep downloads, model scratch files and heatmaps private to this attempt.
    with tempfile.TemporaryDirectory(prefix=f"{SOURCE}-attempt-") as workdir:
        return _process(msg, os.path.join(workdir, "input"), progress_callback)


def _process(msg: dict, input_path: str, progress_callback=None) -> dict:
    if not audio_inference:
        raise RuntimeError("AudioInference module not initialized properly.")

    mode = msg.get("mode", "accurate")
    try:
        # Start-ping before the S3 download: flips the analysis PENDING -> PROCESSING immediately,
        # so a long download doesn't look like a stuck PENDING job (amqp-messages.md).
        if progress_callback:
            progress_callback(0, "LOADING")
        log.info("downloading_file", bucket=msg["file_bucket"], key=msg["file_key"], mode=mode)
        s3_client.download_file(msg["file_bucket"], msg["file_key"], input_path)

        result = audio_inference.analyze(input_path, mode=mode, progress_callback=progress_callback)
    finally:
        # Runs on failure and AnalysisCancelled too, not just the happy path — a 20-minute
        # input left in /tmp per aborted task would fill the container disk.
        if os.path.exists(input_path):
            os.remove(input_path)
    # Contract (amqp-messages.md): gradcam_keys = bare object keys following
    # {analysisId}/{source}/{name}.png from object-storage.md. No URI scheme, no bucket prefix.
    # Retries may disagree with the accepted result; keep its referenced objects immutable.
    attempt_id = uuid.uuid4().hex
    gradcam_keys = []
    if "local_gradcam_path" in result and os.path.exists(result["local_gradcam_path"]):
        gradcam_key = f"{msg['analysis_id']}/{SOURCE}/gradcam_{attempt_id}.png"
        try:
            s3_client.upload_file(
                result["local_gradcam_path"],
                "analysis-artifacts",
                gradcam_key,
                ExtraArgs={"ContentType": "image/png"}
            )
            gradcam_keys.append(gradcam_key)
        except Exception as e:
            log.exception("gradcam_upload_failed", error=str(e))
        finally:
            os.remove(result["local_gradcam_path"])
            del result["local_gradcam_path"]
    result["gradcam_keys"] = gradcam_keys
    return result
def _trace_log_fields(span) -> dict:
    sc = span.get_span_context()
    if not sc.is_valid:
        return {}
    return {"trace_id": format(sc.trace_id, "032x"), "span_id": format(sc.span_id, "016x")}
def _publish(ch, routing_key: str, payload: dict) -> None:
    # Inject the current span context so the Orchestrator's listener joins this trace.
    headers = {}
    _propagator.inject(carrier=headers)
    ch.basic_publish(
        exchange=EXCHANGE,
        routing_key=routing_key,
        body=json.dumps(payload).encode(),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2,
                                        headers=headers or None),
        mandatory=True,
    )
def _handle_message(ch, method, properties, body):
    try:
        msg = json.loads(body)
        analysis_id = msg["analysis_id"]
        correlation_id = msg.get("correlation_id", "")
    except (json.JSONDecodeError, KeyError, TypeError) as e:
        log.error("bad_message_to_dlq", error=str(e), error_type=type(e).__name__)
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
        return
    # Continue the trace the Orchestrator started (W3C traceparent header); a message without
    # the header simply roots a new trace, so tracing can never block processing.
    upstream = _propagator.extract(carrier=properties.headers or {})
    with tracer.start_as_current_span(
            f"{QUEUE} process", context=upstream, kind=SpanKind.CONSUMER,
            attributes={"analysis.id": analysis_id, "analysis.source": SOURCE}) as span:
        structlog.contextvars.bind_contextvars(
            analysis_id=analysis_id,
            correlation_id=correlation_id,
            source=SOURCE,
            **_trace_log_fields(span),
        )
        try:
            # Redis only saves cancelled work; processing markers are not completion evidence.
            if _is_cancelled(analysis_id):
                log.info("task_cancelled_before_start")
                span.set_attribute("analysis.cancelled", True)
                PROCESSED_AUDIO_TOTAL.labels(status="cancelled").inc()
                ch.basic_ack(delivery_tag=method.delivery_tag)
                return

            def progress_callback(pct: int, stage: str = "INFERENCE", details: dict | None = None):
                if _is_cancelled(analysis_id):
                    raise AnalysisCancelled()
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

            log.info("processing_started", redelivered=method.redelivered)
            try:
                result = process(msg, progress_callback=progress_callback)
                if _is_cancelled(analysis_id):
                    raise AnalysisCancelled()
                payload = {
                    "analysis_id": analysis_id,
                    "correlation_id": correlation_id,
                    "source": SOURCE,
                    "status": "COMPLETED",
                    "result": result,
                    "error": None,
                }
            except AnalysisCancelled:
                # The orchestrator has already committed CANCELLED; no result is needed.
                PROCESSED_AUDIO_TOTAL.labels(status="cancelled").inc()
                log.info("processing_cancelled")
                span.set_attribute("analysis.cancelled", True)
                ch.basic_ack(delivery_tag=method.delivery_tag)
                return
            except pika.exceptions.AMQPError:
                # A failed progress publication is a transport failure, not an ML verdict.
                # Close the connection in run_consumer and let RabbitMQ redeliver.
                raise
            except Exception as e:
                PROCESSED_AUDIO_TOTAL.labels(status="error").inc()
                log.exception("processing_failed")
                span.record_exception(e)
                span.set_status(StatusCode.ERROR, str(e))
                payload = {
                    "analysis_id": analysis_id,
                    "correlation_id": correlation_id,
                    "source": SOURCE,
                    "status": "FAILED",
                    "result": None,
                    "error": {"code": "PROCESSING_ERROR", "message": str(e)},
                }

            # BlockingChannel waits for the broker confirm; mandatory returns/nacks raise.
            # Publication and ACK failures escape instead of publishing a spurious FAILED.
            _publish(ch, "analysis.results", payload)
            ch.basic_ack(delivery_tag=method.delivery_tag)
            if payload["status"] == "COMPLETED":
                PROCESSED_AUDIO_TOTAL.labels(status="success").inc()
                log.info("processing_completed", verdict=result["verdict"],
                         prob_fake=result["prob_fake"])
        finally:
            structlog.contextvars.clear_contextvars()


def run_consumer(health_state: dict) -> None:
    while True:
        conn = None
        try:
            creds = pika.PlainCredentials(RABBIT_USER, RABBIT_PASS)
            params = pika.ConnectionParameters(
                host=RABBIT_HOST, port=int(os.getenv("RABBITMQ_PORT", "5672")), credentials=creds,
                heartbeat=30, blocked_connection_timeout=300,
            )
            conn = pika.BlockingConnection(params)
            ch = conn.channel()
            ch.basic_qos(prefetch_count=1)
            ch.confirm_delivery()                                     
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
            # consume() with inactivity_timeout instead of start_consuming(): every iteration
            # (message or idle tick) bumps the heartbeat /health checks, so a wedged consumer
            # thread turns the container unhealthy instead of reporting a stale "connected" flag.
            for method, properties, body in ch.consume(QUEUE, inactivity_timeout=5):
                health_state["last_beat"] = time.time()
                if method is None:
                    continue  # idle tick - heartbeat only
                _handle_message(ch, method, properties, body)
        except Exception as e:
            health_state["ok"] = False
            log.exception("consumer_crashed_reconnecting", error=str(e), backoff_seconds=5)
            # Release unacked deliveries even when a mandatory return leaves the channel open.
            if conn is not None and conn.is_open:
                try:
                    conn.close()
                except pika.exceptions.AMQPError:
                    log.warning("consumer_connection_close_failed")
            time.sleep(5)
