import json
import os
import random
import time

import pika
import structlog

RABBIT_HOST = os.getenv("RABBITMQ_HOST", "rabbitmq")
RABBIT_USER = os.getenv("RABBITMQ_USER", "deepfake")
RABBIT_PASS = os.getenv("RABBITMQ_PASSWORD", "changeme_dev")
QUEUE       = os.getenv("QUEUE_NAME", "analysis.audio")
SOURCE      = os.getenv("SOURCE_LABEL", "audio")   # "video" or "audio"
EXCHANGE    = "analysis.exchange"
DLX         = "analysis.dlx"

log = structlog.get_logger(__name__)

# ============================================================
# REPLACEMENT POINT — swap with the real ML pipeline.
# Input:   msg (dict with analysis_id, file_bucket, file_key, correlation_id)
# Output:  dict matching the contract in docs/contracts/amqp-messages.md
# Scaffolding deps: prometheus-client, boto3 — ready to use, not yet imported.
#
# Fetching the input file from S3 (SeaweedFS) — env vars are set in docker-compose:
#
#     import boto3
#     from botocore.client import Config
#     s3 = boto3.client(
#         "s3",
#         endpoint_url=os.environ["S3_ENDPOINT"],
#         aws_access_key_id=os.environ["S3_ACCESS_KEY"],
#         aws_secret_access_key=os.environ["S3_SECRET_KEY"],
#         region_name=os.environ.get("S3_REGION", "us-east-1"),
#         config=Config(s3={"addressing_style": "path"}),  # SeaweedFS requires path-style addressing
#     )
#     s3.download_file(msg["file_bucket"], msg["file_key"], "/tmp/input")
# ============================================================
def process(msg: dict) -> dict:
    time.sleep(2)  # inference simulation
    prob = round(random.uniform(0.1, 0.95), 4)
    return {
        "prob_fake": prob,
        "verdict": "FAKE" if prob > 0.5 else "REAL",
        "confidence": round(abs(prob - 0.5) * 2, 4),
        "model_version": "dummy-v0.1",
        "gradcam_urls": [],
        "metadata": {},
    }


def _publish(ch, routing_key: str, payload: dict) -> None:
    ch.basic_publish(
        exchange=EXCHANGE,
        routing_key=routing_key,
        body=json.dumps(payload).encode(),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
        mandatory=True,  # with confirm_delivery: raises UnroutableError if no queue is bound to routing_key
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
        # TODO D6: idempotency check via Redis SETNX(processing:{analysis_id}) before publishing results.
        _publish(ch, "analysis.progress", {
            "analysis_id": analysis_id,
            "correlation_id": correlation_id,
            "source": SOURCE,
            "progress": 50,
            "stage": "INFERENCE",
        })
        result = process(msg)
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
        _publish(ch, "analysis.results", {
            "analysis_id": analysis_id,
            "correlation_id": correlation_id,
            "source": SOURCE,
            "status": "FAILED",
            "result": None,
            "error": {"code": "PROCESSING_ERROR", "message": str(e)},
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
            log.info("consumer_ready", queue=QUEUE, source=SOURCE)

            ch.basic_consume(queue=QUEUE, on_message_callback=_handle_message, auto_ack=False)
            ch.start_consuming()
        except Exception as e:
            health_state["ok"] = False
            log.error("consumer_crashed_reconnecting", error=str(e), backoff_seconds=5)
            time.sleep(5)
