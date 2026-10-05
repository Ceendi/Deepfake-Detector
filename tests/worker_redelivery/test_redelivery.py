"""Broker/process regressions. Run only against the isolated Compose project."""
import json
import os
import subprocess
import uuid

import pytest
import redis
from support import ROOT, Worker, connect, declare, publish, result, wait_for


@pytest.fixture(params=["video", "audio"])
def source(request):
    return request.param


@pytest.fixture
def broker():
    connection = connect()
    channel = connection.channel()
    declare(channel)
    for queue in ["analysis.video", "analysis.audio", "analysis.results", "analysis.progress"]:
        channel.queue_purge(queue)
    yield channel
    connection.close()


@pytest.fixture
def rds():
    client = redis.Redis(host=os.getenv("REDIS_HOST", "localhost"), port=int(os.environ["REDIS_PORT"]))
    client.flushdb()  # Disposable Redis from the explicitly named test Compose project only.
    return client


@pytest.fixture
def workers(tmp_path):
    processes = []

    def start(source, **env):
        worker = Worker(source, tmp_path / f"worker-{len(processes)}", **env)
        processes.append(worker)
        return worker

    yield start
    for worker in processes:
        worker.kill()


def test_kill_after_claim_and_stale_marker_recovers_without_redis_cleanup(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    key = f"processing:{analysis_id}:{source}"
    rds.set(key, "1", ex=3600)
    first = workers(source, BLOCK_AT="inference")
    publish(broker, source, analysis_id)
    assert first.wait("claimed")["redelivered"] is False
    first.wait("inference")
    first.kill()
    replacement = workers(source)
    assert replacement.wait("claimed")["redelivered"] is True
    replacement.wait("confirmed")
    replacement.wait("acked")
    assert result(broker)["status"] == "COMPLETED"
    assert rds.get(key) == b"1"
    assert rds.ttl(key) > 3500  # Recovery did not wait for TTL or delete the old version's key.


def test_kill_after_confirm_before_ack_redelivers(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    first = workers(source, BLOCK_AT="confirmed")
    publish(broker, source, analysis_id)
    first.wait("confirmed")
    assert not (first.directory / "before_ack").exists()
    assert result(broker)["status"] == "COMPLETED"
    first.kill()
    replacement = workers(source, MODEL_SCORE="0.1")
    assert replacement.wait("claimed")["redelivered"] is True
    replacement.wait("acked")
    duplicate = result(broker)
    assert duplicate["result"]["prob_fake"] == 0.1
    assert duplicate["source"] == source


def test_two_workers_recompute_concurrent_duplicate_with_private_files(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    first = workers(source, BLOCK_AT="inference")
    publish(broker, source, analysis_id)
    first_input = first.wait("inference")["input_path"]
    second = workers(source, BLOCK_AT="inference")
    publish(broker, source, analysis_id)
    second_input = second.wait("inference")["input_path"]
    assert first_input != second_input
    first.release()
    second.release()
    first.wait("acked")
    second.wait("acked")
    assert [result(broker)["status"] for _ in range(2)] == ["COMPLETED", "COMPLETED"]
    assert not rds.keys("processing:*")


def test_redis_loss_during_work_does_not_lose_delivery(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    worker = workers(source, BLOCK_AT="inference")
    publish(broker, source, analysis_id)
    worker.wait("inference")
    project = os.environ["WORKER_TEST_COMPOSE_PROJECT"]
    compose = ["docker", "compose", "-p", project, "-f", str(ROOT / "tests/worker_redelivery/compose.yml")]
    subprocess.run([*compose, "pause", "redis"], check=True, capture_output=True)
    try:
        worker.release()
        worker.wait("acked")
        assert result(broker)["status"] == "COMPLETED"
    finally:
        subprocess.run([*compose, "unpause", "redis"], check=True, capture_output=True)
        wait_for(lambda: redis_ready(rds))


def redis_ready(client):
    try:
        return client.ping()
    except redis.RedisError:
        return False


@pytest.mark.parametrize("during_work", [False, True])
def test_cooperative_cancel_acks_without_result(source, broker, rds, workers, during_work):
    analysis_id = str(uuid.uuid4())
    if not during_work:
        rds.set(f"cancel:{analysis_id}", "1", ex=7200)
    worker = workers(source, BLOCK_AT="inference" if during_work else "unused")
    publish(broker, source, analysis_id)
    if during_work:
        worker.wait("inference")
        rds.set(f"cancel:{analysis_id}", "1", ex=7200)
        worker.release()
    worker.wait("acked")
    assert broker.basic_get("analysis.results", auto_ack=True)[0] is None
    if not during_work:
        assert not (worker.directory / "inference").exists()


@pytest.mark.parametrize("failed_route", ["analysis.results", "analysis.progress"])
def test_unroutable_publication_reconnects_and_redelivers_without_failed_result(
        source, broker, rds, workers, failed_route):
    analysis_id = str(uuid.uuid4())
    worker = workers(source, BLOCK_AT="inference")
    publish(broker, source, analysis_id)
    worker.wait("inference")
    broker.queue_unbind(queue=failed_route, exchange="analysis.exchange", routing_key=failed_route)
    worker.release()
    worker.wait("acked")  # Reconnect redeclares bindings after a real mandatory return.
    events = [json.loads(line) for line in (worker.directory / "events.jsonl").read_text().splitlines()]
    deliveries = [entry for entry in events if entry["event"] == "claimed"]
    assert len(deliveries) == 2 and deliveries[1]["redelivered"] is True
    assert [entry["status"] for entry in events if entry["event"] == "confirmed"] == ["COMPLETED"]
    assert result(broker)["status"] == "COMPLETED"
    assert broker.basic_get("analysis.results", auto_ack=True)[0] is None
