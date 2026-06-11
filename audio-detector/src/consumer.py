import json
import os
import time
import pika
import structlog
import boto3
import redis
from botocore.client import Config
from prometheus_client import Counter
from .inference import AudioInference
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
    port=int(os.environ.get("REDIS_PORT", 6379)),
    password=os.environ.get("REDIS_PASSWORD"),
    db=0,
    decode_responses=True
)
try:
    audio_inference = AudioInference()
except Exception as e:
    log.error("Failed to load AudioInference", error=str(e))
    audio_inference = None
def process(msg: dict, progress_callback=None) -> dict:
    if not audio_inference:
        raise RuntimeError("AudioInference module not initialized properly.")
    
    mode = msg.get("mode", "accurate")
    input_path = f"/tmp/{msg['analysis_id']}_input"
    log.info("downloading_file", bucket=msg["file_bucket"], key=msg["file_key"], mode=mode)
    s3_client.download_file(msg["file_bucket"], msg["file_key"], input_path)
    
    result = audio_inference.analyze(input_path, mode=mode, progress_callback=progress_callback)
    gradcam_url = None
    if "local_gradcam_path" in result and os.path.exists(result["local_gradcam_path"]):
        gradcam_key = f"{msg['analysis_id']}/gradcam.png"
        try:
            s3_client.upload_file(
                result["local_gradcam_path"], 
                "analysis-artifacts", 
                gradcam_key,
                ExtraArgs={"ContentType": "image/png"}
            )
            gradcam_url = f"minio://analysis-artifacts/{gradcam_key}"
        except Exception as e:
            log.error("gradcam_upload_failed", error=str(e))
        finally:
            os.remove(result["local_gradcam_path"])
            del result["local_gradcam_path"]
    if os.path.exists(input_path):
        os.remove(input_path)
    result["gradcam_url"] = gradcam_url
    return result
def _publish(ch, routing_key: str, payload: dict) -> None:
    ch.basic_publish(
        exchange=EXCHANGE,
        routing_key=routing_key,
        body=json.dumps(payload).encode(),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
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
    structlog.contextvars.bind_contextvars(
        analysis_id=analysis_id,
        correlation_id=correlation_id,
        source=SOURCE,
    )
    try:
        log.info("processing_started")
        if not redis_client.setnx(f"processing:{analysis_id}", "1"):
            log.warning("duplicate_message_dropped", reason="already_processing")
            ch.basic_ack(delivery_tag=method.delivery_tag)
            return
        redis_client.expire(f"processing:{analysis_id}", 3600)
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
        result = process(msg, progress_callback=progress_callback)
        _publish(ch, "analysis.results", {
            "analysis_id": analysis_id,
            "correlation_id": correlation_id,
            "source": SOURCE,
            "status": "COMPLETED",
            "result": result,
            "error": None,
        })
        ch.basic_ack(delivery_tag=method.delivery_tag)
        PROCESSED_AUDIO_TOTAL.labels(status="success").inc()
        log.info("processing_completed", verdict=result["verdict"], prob_fake=result["prob_fake"])
    except Exception as e:
        PROCESSED_AUDIO_TOTAL.labels(status="error").inc()
        log.exception("processing_failed")
        _publish(ch, "analysis.results", {
            "analysis_id": analysis_id,
            "correlation_id": correlation_id,
            "source": SOURCE,
            "status": "FAILED",
            "result": None,
            "error": {"code": "PROCESSING_ERROR", "message": str(e)},
        })
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
            log.info("consumer_ready", queue=QUEUE, source=SOURCE)
            ch.basic_consume(queue=QUEUE, on_message_callback=_handle_message, auto_ack=False)
            ch.start_consuming()
        except Exception as e:
            health_state["ok"] = False
            log.error("consumer_crashed_reconnecting", error=str(e), backoff_seconds=5)
            time.sleep(5)
