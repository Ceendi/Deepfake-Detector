"""Real production consumer with a lightweight inference/S3 input fixture and barriers.

No consumer, publish, confirm or ACK is mocked. Hooks delegate to real pika operations.
This executable is test-only and never imported by a deployed detector.
"""
import json
import os
import sys
import threading
import time
import types
from pathlib import Path

from botocore.exceptions import ClientError, EndpointConnectionError

source, directory = sys.argv[1:]
state = Path(directory)
state.mkdir(parents=True, exist_ok=True)


def event(name, **fields):
    with (state / "events.jsonl").open("a") as stream:
        stream.write(json.dumps({"event": name, **fields}) + "\n")
    (state / name).write_text(json.dumps(fields))
    if os.getenv("BLOCK_AT") == name and not (state / "release").exists():
        deadline = time.monotonic() + 45
        while not (state / "release").exists():
            if time.monotonic() > deadline:
                raise TimeoutError(f"Unreleased test barrier: {name}")
            time.sleep(0.01)


class ControlledInference:
    def __init__(self):
        if os.getenv("MODEL_UNAVAILABLE") == "true":
            raise RuntimeError("controlled model initialization failure")

    def analyze(self, input_path, progress_callback, **kwargs):
        assert Path(input_path).read_bytes() == b"controlled test input"
        event("inference", input_path=input_path, workdir=kwargs.get("workdir"))
        assert Path(input_path).read_bytes() == b"controlled test input"
        progress_callback(50, "INFERENCE")
        if os.getenv("MODEL_FAIL") == "true":
            raise RuntimeError("controlled model failure")
        score = float(os.getenv("MODEL_SCORE", "0.8" if source == "video" else "0.4"))
        heatmap = Path(input_path).with_name("controlled-gradcam.png")
        heatmap.write_bytes(f"{state.name}:{score}".encode())
        result = {
            "prob_fake": score, "verdict": "FAKE" if score > 0.5 else "REAL",
            "confidence": 2 * abs(score - 0.5), "model_version": "controlled-test-model",
            "metadata": {"attempt": state.name, "raw_prob_fake": 0.24,
                         "threshold_used": 0.3, "score_contract": "audio-threshold-v1"},
        }
        if source == "video":
            result["local_gradcam_paths"] = [(str(heatmap), 1)]
        else:
            result["local_gradcam_path"] = str(heatmap)
        return result


inference = types.ModuleType("src.inference")
setattr(inference, "VideoInference" if source == "video" else "AudioInference", ControlledInference)
sys.modules["src.inference"] = inference
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / f"{source}-detector"))
# Storage keys are mandatory at import; this fixture replaces the S3 client before any call.
os.environ.setdefault("S3_ACCESS_KEY", "test")
os.environ.setdefault("S3_SECRET_KEY", "test")
from detector_runtime import consumer as runtime_module
from src import consumer, main

# Speed up broker heartbeat and health expiry only in this isolated process fixture.
if os.getenv("TEST_HEARTBEAT_SECONDS"):
    real_parameters = runtime_module.pika.ConnectionParameters

    def test_parameters(**kwargs):
        kwargs["heartbeat"] = int(os.environ["TEST_HEARTBEAT_SECONDS"])
        return real_parameters(**kwargs)

    runtime_module.pika.ConnectionParameters = test_parameters
    main.HEALTH_MAX_SILENCE_SECONDS = int(os.environ["TEST_HEARTBEAT_SECONDS"])
# Shorten reconnect backoff so storage outages reach the delivery limit within the test.
if os.getenv("TEST_RECONNECT_SECONDS"):
    runtime_module.RECONNECT_DELAY_SECONDS = float(os.environ["TEST_RECONNECT_SECONDS"])
    runtime_module.STORAGE_BACKOFF_MAX_SECONDS = float(os.environ["TEST_RECONNECT_SECONDS"])


def observe_health():
    while True:
        response = main.health()
        snapshot = {**consumer.runtime.health_state,
                    "status": response.get("status") if isinstance(response, dict) else "DOWN"}
        temporary = state / "health.tmp"
        temporary.write_text(json.dumps(snapshot))
        temporary.replace(state / "health.json")
        time.sleep(0.1)


class ControlledInput:
    def __init__(self):
        self.outages = int(os.getenv("S3_DOWNLOAD_OUTAGES", "0"))

    def download_file(self, bucket, key, destination):
        event("download")
        if self.outages > 0:
            self.outages -= 1
            raise EndpointConnectionError(endpoint_url="http://seaweedfs:8333")
        if os.getenv("S3_DOWNLOAD_MISSING") == "true":
            raise ClientError({"Error": {"Code": "404", "Message": "Not Found"},
                               "ResponseMetadata": {"HTTPStatusCode": 404}}, "HeadObject")
        Path(destination).write_bytes(b"controlled test input")

    def upload_file(self, local_path, bucket, key, **kwargs):
        event("upload")
        # A shared filesystem object-store fixture makes accidental overwrites observable.
        root = Path(os.getenv("ARTIFACT_STORE", str(state / "artifacts")))
        destination = root / bucket / key
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(Path(local_path).read_bytes())


consumer.runtime.s3_client = ControlledInput()
real_handle = consumer.runtime.handle_message
real_publish = consumer.runtime.publish


def observed_handle(channel, method, properties, body):
    event("claimed", redelivered=method.redelivered, delivery_tag=method.delivery_tag,
          delivery_count=(properties.headers or {}).get("x-delivery-count", 0))
    real_ack = channel.basic_ack

    def observed_ack(**kwargs):
        event("before_ack")
        real_ack(**kwargs)
        # A synchronous broker response on the same channel follows the real ACK frame.
        channel.queue_declare(queue=consumer.QUEUE, passive=True)
        event("acked")

    channel.basic_ack = observed_ack
    try:
        return real_handle(channel, method, properties, body)
    finally:
        channel.basic_ack = real_ack


def observed_publish(channel, routing_key, payload):
    real_publish(channel, routing_key, payload)
    if routing_key == "analysis.results":
        event("confirmed", status=payload["status"], result=payload.get("result"))


consumer.runtime.handle_message = observed_handle
consumer.runtime.publish = observed_publish
threading.Thread(target=observe_health, daemon=True).start()
consumer.run_consumer()
