import json
import os
import subprocess
import sys
import time
from pathlib import Path

import pika

ROOT = Path(__file__).resolve().parents[2]


def wait_for(predicate, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(0.02)
    raise AssertionError("Timed out waiting for real broker/worker state")


class Worker:
    def __init__(self, source, directory, **env):
        self.directory = directory
        directory.mkdir()
        self.log = (directory / "worker.log").open("w")
        self.process = subprocess.Popen(
            [sys.executable, str(ROOT / "tests/worker_redelivery/worker.py"), source, str(directory)],
            env={**os.environ, **env, "SOURCE_LABEL": source, "QUEUE_NAME": f"analysis.{source}"},
            stdout=self.log, stderr=subprocess.STDOUT,
        )

    def wait(self, name):
        def check():
            assert self.process.poll() is None, (self.directory / "worker.log").read_text()
            path = self.directory / name
            return path.exists() and path.read_text()
        try:
            return json.loads(wait_for(check))
        except AssertionError as error:
            raise AssertionError((self.directory / "worker.log").read_text()) from error

    def release(self):
        (self.directory / "release").touch()

    def kill(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait(timeout=10)
        self.log.close()


def connect():
    return pika.BlockingConnection(pika.ConnectionParameters(
        host=os.getenv("RABBITMQ_HOST", "localhost"),
        port=int(os.environ["RABBITMQ_PORT"]),
        credentials=pika.PlainCredentials("test", "test"),
        connection_attempts=60, retry_delay=0.25,
    ))


def declare(channel):
    channel.exchange_declare(exchange="analysis.exchange", exchange_type="topic", durable=True)
    channel.exchange_declare(exchange="analysis.dlx", exchange_type="direct", durable=True)
    for source in ["video", "audio"]:
        queue = f"analysis.{source}"
        channel.queue_declare(queue=queue, durable=True, arguments={
            "x-dead-letter-exchange": "analysis.dlx", "x-dead-letter-routing-key": queue + ".dlq"})
        channel.queue_bind(queue=queue, exchange="analysis.exchange", routing_key=queue)
    for queue in ["analysis.results", "analysis.progress"]:
        channel.queue_declare(queue=queue, durable=True)
        channel.queue_bind(queue=queue, exchange="analysis.exchange", routing_key=queue)
    channel.confirm_delivery()


def publish(channel, source, analysis_id):
    channel.basic_publish(exchange="analysis.exchange", routing_key=f"analysis.{source}",
                          body=json.dumps({"analysis_id": analysis_id, "correlation_id": "pr06",
                                           "file_bucket": "test", "file_key": "input", "mode": "accurate"}),
                          properties=pika.BasicProperties(delivery_mode=2, content_type="application/json"),
                          mandatory=True)


def result(channel):
    def receive():
        method, _, body = channel.basic_get("analysis.results", auto_ack=True)
        return json.loads(body) if method else None
    return wait_for(receive)
