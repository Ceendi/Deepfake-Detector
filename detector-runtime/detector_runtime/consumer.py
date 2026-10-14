import json
import math
import os
import re
import sys
import tempfile
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import nullcontext
from dataclasses import dataclass, field
from threading import Event, Thread

import pika
import redis
import structlog
from opentelemetry.trace import SpanKind, StatusCode
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

from .connection import ConnectionChannel
from .errors import DetectorError, ModelUnavailableError, StorageUnavailableError
from .storage import is_transient_storage_error, required_env

EXCHANGE = "analysis.exchange"
DLX = "analysis.dlx"
ARTIFACTS_BUCKET = "analysis-artifacts"
PROCESSING_ERROR_MESSAGE = "Nie udało się przetworzyć materiału. Spróbuj ponownie."
RECONNECT_DELAY_SECONDS = 5
STORAGE_BACKOFF_MAX_SECONDS = 30
# A failed cancel check costs a full socket timeout; skip Redis briefly instead of on every tick.
REDIS_BACKOFF_SECONDS = 5
WATCHDOG_INTERVAL_SECONDS = 1
_FILE_ID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")


def _task_hard_limit_seconds():
    limit = float(os.getenv("DETECTOR_TASK_HARD_LIMIT_SECONDS", "3600"))
    if not math.isfinite(limit) or limit <= 0:
        raise ValueError("Detector task hard limit must be finite and positive")
    return limit


class AnalysisCancelled(Exception):
    """Task aborted because the user cancelled the analysis."""


@dataclass
class Artifact:
    path: str
    key: str
    cleanup_field: str | None = None
    missing_ok: bool = True
    log_fields: dict = field(default_factory=dict)


class DetectorRuntime:
    def __init__(self, *, source, queue, log, tracer, s3_client, redis_client,
                 process_task, record_outcome, inference_timer=nullcontext,
                 progress_interval_seconds=0):
        self.source = source
        self.queue = queue
        self.log = log
        self.tracer = tracer
        self.s3_client = s3_client
        self.redis_client = redis_client
        self.process_task = process_task
        self.record_outcome = record_outcome
        self.inference_timer = inference_timer
        self.progress_interval_seconds = progress_interval_seconds
        self.health_state = {"ok": False, "last_beat": 0.0}
        self.propagator = TraceContextTextMapPropagator()
        self.rabbit_host = os.getenv("RABBITMQ_HOST", "rabbitmq")
        self.rabbit_user = os.getenv("RABBITMQ_USER", "deepfake")
        self.task_hard_limit_seconds = _task_hard_limit_seconds()
        self.exit_process = os._exit
        self._task_started = None
        self._redis_retry_at = 0.0
        self._storage_failures = 0
        self._watchdog = None

    def is_cancelled(self, analysis_id):
        # Redis saves compute; the database terminal guard remains authoritative.
        now = time.monotonic()
        if now < self._redis_retry_at:
            return False
        try:
            return self.redis_client.exists(f"cancel:{analysis_id}") > 0
        except redis.RedisError:
            self._redis_retry_at = now + REDIS_BACKOFF_SECONDS
            self.log.warning("cancel_check_unavailable", retry_in_seconds=REDIS_BACKOFF_SECONDS)
            return False

    def process(self, msg, *, model, analyze, artifacts, progress_callback=None,
                download_fields=None):
        # Attempts may run concurrently in processes sharing /tmp.
        with tempfile.TemporaryDirectory(prefix=f"{self.source}-attempt-") as workdir:
            if model is None:
                raise ModelUnavailableError()
            input_path = os.path.join(workdir, "input")
            try:
                if progress_callback:
                    progress_callback(0, "LOADING")
                # Object keys may carry the user's file name; log only the file UUID.
                file_id = _FILE_ID.match(str(msg["file_key"]))
                self.log.info("downloading_file", bucket=msg["file_bucket"],
                              file_id=file_id.group(0) if file_id else None,
                              **(download_fields or {}))
                try:
                    self.s3_client.download_file(msg["file_bucket"], msg["file_key"], input_path)
                except Exception as e:
                    if is_transient_storage_error(e):
                        raise StorageUnavailableError("Input download failed") from e
                    raise
                result = analyze(model, msg, input_path, progress_callback)
            finally:
                if os.path.exists(input_path):
                    os.remove(input_path)

            # An attempt UUID prevents retries from overwriting an accepted result's images.
            gradcam_keys = []
            for artifact in artifacts(msg, result, uuid.uuid4().hex):
                try:
                    self.s3_client.upload_file(artifact.path, ARTIFACTS_BUCKET, artifact.key,
                                               ExtraArgs={"ContentType": "image/png"})
                    gradcam_keys.append(artifact.key)
                except Exception as e:
                    # An outage must not complete the analysis without its heatmaps.
                    if is_transient_storage_error(e):
                        raise StorageUnavailableError("Artifact upload failed") from e
                    self.log.exception("gradcam_upload_failed", error=str(e), **artifact.log_fields)
                finally:
                    if not artifact.missing_ok or os.path.exists(artifact.path):
                        os.remove(artifact.path)
                    if artifact.cleanup_field is not None:
                        del result[artifact.cleanup_field]
            result["gradcam_keys"] = gradcam_keys
            return result

    def publish(self, ch, routing_key, payload):
        headers = {}
        self.propagator.inject(carrier=headers)
        ch.basic_publish(
            exchange=EXCHANGE, routing_key=routing_key,
            body=json.dumps(payload).encode(),
            properties=pika.BasicProperties(content_type="application/json", delivery_mode=2,
                                            headers=headers or None),
            mandatory=True,
        )

    def handle_message(self, ch, method, properties, body):
        try:
            msg = json.loads(body)
            analysis_id = msg["analysis_id"]
            correlation_id = msg.get("correlation_id", "")
        except (ValueError, KeyError, TypeError, RecursionError) as e:
            # ValueError also covers non-UTF-8 bodies; RecursionError covers deep nesting.
            self.log.error("bad_message_to_dlq", error=str(e), error_type=type(e).__name__)
            ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            return

        upstream = self.propagator.extract(carrier=properties.headers or {})
        with self.tracer.start_as_current_span(
                f"{self.queue} process", context=upstream, kind=SpanKind.CONSUMER,
                attributes={"analysis.id": analysis_id, "analysis.source": self.source}) as span:
            sc = span.get_span_context()
            trace_fields = ({"trace_id": format(sc.trace_id, "032x"),
                             "span_id": format(sc.span_id, "016x")} if sc.is_valid else {})
            structlog.contextvars.bind_contextvars(
                analysis_id=analysis_id, correlation_id=correlation_id, source=self.source,
                **trace_fields,
            )
            try:
                if self.is_cancelled(analysis_id):
                    self.log.info("task_cancelled_before_start")
                    span.set_attribute("analysis.cancelled", True)
                    self.record_outcome("cancelled", None)
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                    return

                last_progress = None

                def progress_callback(pct: int, stage: str = "INFERENCE",
                                      details: dict | None = None):
                    nonlocal last_progress
                    if self.is_cancelled(analysis_id):
                        raise AnalysisCancelled()
                    now = time.monotonic()
                    final_tick = (pct == 100 or (details is not None
                                  and details.get("current_segment") is not None
                                  and details.get("current_segment") == details.get("total_segments")))
                    # Cancellation is checked even on ticks whose publication is suppressed.
                    if (last_progress is not None and self.progress_interval_seconds > 0
                            and stage == last_progress[1] and not final_tick
                            and pct < last_progress[0] + 1
                            and now - last_progress[2] < self.progress_interval_seconds):
                        return
                    payload = {
                        "analysis_id": analysis_id, "correlation_id": correlation_id,
                        "source": self.source, "progress": pct, "stage": stage,
                    }
                    if details is not None:
                        payload["details"] = details
                    self.publish(ch, "analysis.progress", payload)
                    last_progress = (pct, stage, now)

                self.log.info("processing_started", redelivered=method.redelivered)
                try:
                    with self.inference_timer():
                        result = self.process_task(msg, progress_callback=progress_callback)
                    if self.is_cancelled(analysis_id):
                        raise AnalysisCancelled()
                    payload = {
                        "analysis_id": analysis_id, "correlation_id": correlation_id,
                        "source": self.source, "status": "COMPLETED", "result": result,
                        "error": None,
                    }
                except AnalysisCancelled:
                    self.record_outcome("cancelled", None)
                    self.log.info("processing_cancelled")
                    span.set_attribute("analysis.cancelled", True)
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                    return
                except pika.exceptions.AMQPError:
                    # A progress transport failure must redeliver, never become an ML verdict.
                    raise
                except StorageUnavailableError as e:
                    # No traceback: botocore endpoint URLs carry the object key (user file name).
                    self.log.warning("storage_unavailable_redelivering",
                                     error_type=type(e.__cause__).__name__)
                    raise
                except Exception as e:
                    self.log.exception("processing_failed")
                    span.record_exception(e)
                    span.set_status(StatusCode.ERROR, str(e))
                    # Foreign exceptions may carry numeric codes and paths (e.g. cv2.error).
                    domain = isinstance(e, DetectorError) and isinstance(e.code, str)
                    payload = {
                        "analysis_id": analysis_id, "correlation_id": correlation_id,
                        "source": self.source, "status": "FAILED", "result": None,
                        "error": {"code": e.code if domain else "PROCESSING_ERROR",
                                  "message": str(e) if domain else PROCESSING_ERROR_MESSAGE},
                    }

                # The blocking publish awaits confirm; publish and ACK failures must escape.
                self.publish(ch, "analysis.results", payload)
                ch.basic_ack(delivery_tag=method.delivery_tag)
                self._storage_failures = 0
                if payload["status"] == "COMPLETED":
                    self.record_outcome("completed", result)
                    self.log.info("processing_completed", verdict=result["verdict"],
                                  prob_fake=result["prob_fake"])
                else:
                    self.record_outcome("failed", None)
            finally:
                structlog.contextvars.clear_contextvars()

    def declare_topology(self, ch):
        # Arguments must match the orchestrator: docs/contracts/amqp-messages.md.
        ch.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
        ch.exchange_declare(exchange=DLX, exchange_type="direct", durable=True)
        ch.queue_declare(queue=self.queue, durable=True, arguments={
            "x-queue-type": "quorum",
            "x-delivery-limit": 5,
            "x-dead-letter-exchange": DLX,
            "x-dead-letter-routing-key": f"{self.queue}.dlq",
        })
        ch.queue_declare(queue=f"{self.queue}.dlq", durable=True)
        ch.queue_declare(queue="analysis.results", durable=True)
        ch.queue_declare(queue="analysis.progress", durable=True)
        ch.queue_bind(queue=self.queue, exchange=EXCHANGE, routing_key=self.queue)
        ch.queue_bind(queue=f"{self.queue}.dlq", exchange=DLX, routing_key=f"{self.queue}.dlq")
        ch.queue_bind(queue="analysis.results", exchange=EXCHANGE, routing_key="analysis.results")
        ch.queue_bind(queue="analysis.progress", exchange=EXCHANGE, routing_key="analysis.progress")

    def run_task(self, ch, method, properties, body):
        self._task_started = time.monotonic()
        try:
            return self.handle_message(ch, method, properties, body)
        finally:
            self._task_started = None

    def check_task_deadline(self, health_state=None):
        started = self._task_started
        if started is None or time.monotonic() - started <= self.task_hard_limit_seconds:
            return
        # A hung native call (ffmpeg, ONNX) cannot be interrupted; restart the process so
        # RabbitMQ redelivers the task within its delivery limit.
        (health_state if health_state is not None else self.health_state)["ok"] = False
        self.log.critical("task_hard_limit_exceeded",
                          limit_seconds=self.task_hard_limit_seconds,
                          elapsed_seconds=round(time.monotonic() - started))
        sys.stdout.flush()
        sys.stderr.flush()
        self.exit_process(1)

    def _start_watchdog(self, health_state):
        # A separate thread still fires while the I/O thread drains a hung attempt.
        # Event.wait, not time.sleep: tests and the reconnect loop may patch sleep.
        def watch():
            tick = Event()
            while not tick.wait(WATCHDOG_INTERVAL_SECONDS):
                self.check_task_deadline(health_state)

        if self._watchdog is None:
            self._watchdog = Thread(target=watch, name=f"{self.source}-watchdog", daemon=True)
            self._watchdog.start()

    def reconnect_delay(self, error):
        if not isinstance(error, StorageUnavailableError):
            return RECONNECT_DELAY_SECONDS
        self._storage_failures += 1
        return min(STORAGE_BACKOFF_MAX_SECONDS,
                   RECONNECT_DELAY_SECONDS * 2 ** (self._storage_failures - 1))

    def run_consumer(self, health_state=None):
        if health_state is None:
            health_state = self.health_state
        self._start_watchdog(health_state)
        while True:
            conn = None
            channel = None
            executor = None
            deliveries = []
            delay = RECONNECT_DELAY_SECONDS
            try:
                creds = pika.PlainCredentials(self.rabbit_user, required_env("RABBITMQ_PASSWORD"))
                params = pika.ConnectionParameters(
                    host=self.rabbit_host,
                    port=int(os.getenv("RABBITMQ_PORT", "5672")), credentials=creds,
                    heartbeat=30, blocked_connection_timeout=300,
                )
                conn = pika.BlockingConnection(params)
                ch = conn.channel()
                ch.basic_qos(prefetch_count=1)
                ch.confirm_delivery()
                self.declare_topology(ch)
                channel = ConnectionChannel(conn, ch)
                # One model invocation at a time; pika stays exclusively on this I/O thread.
                executor = ThreadPoolExecutor(max_workers=1,
                                              thread_name_prefix=f"{self.source}-inference")

                def dispatch(ch, method, properties, body, *, executor=executor,
                             channel=channel, deliveries=deliveries):
                    deliveries.append(executor.submit(self.run_task, channel,
                                                      method, properties, body))

                ch.basic_consume(queue=self.queue, on_message_callback=dispatch, auto_ack=False)
                health_state["ok"] = True
                health_state["last_beat"] = time.time()
                self.log.info("consumer_ready", queue=self.queue, source=self.source)
                while True:
                    conn.process_data_events(time_limit=1)
                    for delivery in deliveries[:]:
                        if delivery.done():
                            delivery.result()
                            deliveries.remove(delivery)
                    health_state["last_beat"] = time.time()
            except Exception as e:
                health_state["ok"] = False
                delay = self.reconnect_delay(e)
                if isinstance(e, StorageUnavailableError):
                    self.log.warning("storage_unavailable_reconnecting", backoff_seconds=delay)
                else:
                    self.log.exception("consumer_crashed_reconnecting", error=str(e),
                                       backoff_seconds=delay)
            finally:
                health_state["ok"] = False
                if channel is not None:
                    channel.close()
                if conn is not None and conn.is_open:
                    try:
                        conn.close()
                    except pika.exceptions.AMQPError:
                        self.log.warning("consumer_connection_close_failed")
                if executor is not None:
                    # Drain the abandoned attempt before reconnecting and reusing its model.
                    executor.shutdown(wait=True, cancel_futures=True)
            time.sleep(delay)
