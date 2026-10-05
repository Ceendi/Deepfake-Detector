"""Real production consumer with a lightweight inference/S3 input fixture and barriers.

No consumer, publish, confirm or ACK is mocked. Hooks delegate to real pika operations.
This executable is test-only and never imported by a deployed detector.
"""
import json
import os
import sys
import time
import types
from pathlib import Path

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
    def analyze(self, input_path, progress_callback, **kwargs):
        assert Path(input_path).read_bytes() == b"controlled test input"
        event("inference", input_path=input_path, workdir=kwargs.get("workdir"))
        assert Path(input_path).read_bytes() == b"controlled test input"
        progress_callback(50, "INFERENCE")
        if os.getenv("MODEL_FAIL") == "true":
            raise RuntimeError("controlled model failure")
        score = float(os.getenv("MODEL_SCORE", "0.8" if source == "video" else "0.4"))
        return {
            "prob_fake": score, "verdict": "FAKE" if score > 0.5 else "REAL",
            "confidence": 2 * abs(score - 0.5), "model_version": "controlled-test-model",
            "metadata": {"attempt": state.name, "raw_prob_fake": 0.24,
                         "threshold_used": 0.3, "score_contract": "audio-threshold-v1"},
        }


inference = types.ModuleType("src.inference")
setattr(inference, "VideoInference" if source == "video" else "AudioInference", ControlledInference)
sys.modules["src.inference"] = inference
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / f"{source}-detector"))
from src import consumer


class ControlledInput:
    def download_file(self, bucket, key, destination):
        Path(destination).write_bytes(b"controlled test input")


consumer.s3_client = ControlledInput()
real_handle = consumer._handle_message
real_publish = consumer._publish


def observed_handle(channel, method, properties, body):
    event("claimed", redelivered=method.redelivered, delivery_tag=method.delivery_tag)
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


consumer._handle_message = observed_handle
consumer._publish = observed_publish
consumer.run_consumer({})
