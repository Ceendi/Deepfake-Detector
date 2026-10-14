"""Broker/process regressions. Run only against the isolated Compose project."""
import json
import os
import subprocess
import time
import uuid

import pika
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
    try:
        connection.close()
    except pika.exceptions.AMQPConnectionError:
        pass  # A broker-restart regression deliberately disconnects this fixture too.


@pytest.fixture
def rds():
    client = redis.Redis(host=os.getenv("REDIS_HOST", "localhost"), port=int(os.environ["REDIS_PORT"]))
    client.flushdb()  # Disposable Redis from the explicitly named test Compose project only.
    return client


@pytest.fixture
def workers(tmp_path):
    processes = []
    artifact_store = tmp_path / "artifacts"

    def start(source, **env):
        worker = Worker(source, tmp_path / f"worker-{len(processes)}",
                        ARTIFACT_STORE=str(artifact_store), **env)
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
    accepted = result(broker)
    assert accepted["status"] == "COMPLETED"
    accepted_key = accepted["result"]["gradcam_keys"][0]
    artifact = first.directory.parent / "artifacts" / "analysis-artifacts" / accepted_key
    accepted_bytes = artifact.read_bytes()
    first.kill()
    replacement = workers(source, MODEL_SCORE="0.1")
    assert replacement.wait("claimed")["redelivered"] is True
    replacement.wait("acked")
    duplicate = result(broker)
    assert duplicate["result"]["prob_fake"] == 0.1
    assert duplicate["source"] == source
    duplicate_key = duplicate["result"]["gradcam_keys"][0]
    assert duplicate_key != accepted_key
    assert artifact.read_bytes() == accepted_bytes
    assert (artifact.parents[3] / "analysis-artifacts" / duplicate_key).read_bytes() != accepted_bytes


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


def test_broker_disconnect_during_inference_redelivers_without_stale_result(source, broker, rds, workers):
    worker = workers(source, BLOCK_AT="inference", TEST_HEARTBEAT_SECONDS="2")
    analysis_id = str(uuid.uuid4())
    publish(broker, source, analysis_id)
    worker.wait("inference")
    project = os.environ["WORKER_TEST_COMPOSE_PROJECT"]
    compose = ["docker", "compose", "-p", project, "-f", str(ROOT / "tests/worker_redelivery/compose.yml")]
    # Restart the broker application, preserving the fixture's ephemeral host port.
    subprocess.run([*compose, "exec", "-T", "rabbit", "rabbitmqctl", "stop_app"],
                   check=True, capture_output=True, timeout=30)
    try:
        wait_for(lambda: not json.loads((worker.directory / "health.json").read_text())["ok"])
        worker.release()
    finally:
        subprocess.run([*compose, "exec", "-T", "rabbit", "rabbitmqctl", "start_app"],
                       check=True, capture_output=True, timeout=30)
    worker.wait("acked")
    events = [json.loads(line) for line in (worker.directory / "events.jsonl").read_text().splitlines()]
    assert [entry["redelivered"] for entry in events if entry["event"] == "claimed"] == [False, True]
    assert [entry["status"] for entry in events if entry["event"] == "confirmed"] == ["COMPLETED"]
    # The fixture's original channel also lost its connection when the broker stopped.
    connection = connect()
    try:
        channel = connection.channel()
        assert result(channel)["analysis_id"] == analysis_id
        assert channel.basic_get("analysis.results", auto_ack=True)[0] is None
    finally:
        connection.close()


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



def test_unavailable_model_consumes_and_confirms_failed_without_input(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    worker = workers(source, MODEL_UNAVAILABLE="true", BLOCK_AT="confirmed")
    publish(broker, source, analysis_id)
    worker.wait("claimed")
    worker.wait("confirmed")
    assert not (worker.directory / "before_ack").exists()
    payload = result(broker)
    assert payload == {"analysis_id": analysis_id, "correlation_id": "pr06", "source": source,
                       "status": "FAILED", "result": None,
                       "error": {"code": "MODEL_UNAVAILABLE",
                                 "message": "Usługa analizy jest chwilowo niedostępna. "
                                            "Spróbuj ponownie później."}}
    worker.release()
    worker.wait("acked")
    for operation in ("download", "inference", "upload"):
        assert not (worker.directory / operation).exists()
    assert broker.basic_get("analysis.progress", auto_ack=True)[0] is None
    assert not (worker.directory.parent / "artifacts").exists()


@pytest.mark.parametrize("operation", ["download", "inference", "upload"])
def test_long_blocking_work_keeps_heartbeat_health_and_single_delivery(
        source, broker, rds, workers, operation):
    worker = workers(source, BLOCK_AT=operation, TEST_HEARTBEAT_SECONDS="2")
    analysis_id = str(uuid.uuid4())
    publish(broker, source, analysis_id)
    worker.wait(operation)
    started = time.time()
    # Exceed two broker heartbeats and the API's test health threshold without progress.
    time.sleep(10)
    health = json.loads((worker.directory / "health.json").read_text())
    worker.release()
    worker.wait("acked")
    events = [json.loads(line) for line in (worker.directory / "events.jsonl").read_text().splitlines()]
    deliveries = [entry for entry in events if entry["event"] == "claimed"]
    assert len(deliveries) == 1 and deliveries[0]["redelivered"] is False
    assert health["status"] == "UP"
    assert health["last_beat"] > started + 5
    payload = result(broker)
    assert payload["analysis_id"] == analysis_id
    assert payload["status"] == "COMPLETED"
    assert broker.basic_get("analysis.results", auto_ack=True)[0] is None


def test_repeated_worker_crashes_reach_dlq_and_release_the_queue(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    publish(broker, source, analysis_id)
    for failures in range(6):
        worker = workers(source, BLOCK_AT="inference")
        claimed = worker.wait("claimed")
        assert claimed["delivery_count"] == failures
        worker.wait("inference")
        worker.kill()

    def dead_letter():
        method, properties, body = broker.basic_get(f"analysis.{source}.dlq", auto_ack=True)
        return (properties, json.loads(body)) if method else None

    properties, payload = wait_for(dead_letter)
    assert payload["analysis_id"] == analysis_id
    assert properties.headers["x-first-death-reason"] == "delivery_limit"
    assert broker.queue_declare(queue=f"analysis.{source}", passive=True).method.message_count == 0
    healthy_id = str(uuid.uuid4())
    healthy = workers(source)
    publish(broker, source, healthy_id)
    healthy.wait("acked")
    assert result(broker)["analysis_id"] == healthy_id


def events_of(worker):
    return [json.loads(line) for line in (worker.directory / "events.jsonl").read_text().splitlines()]


def test_storage_outage_redelivers_and_completes(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    worker = workers(source, S3_DOWNLOAD_OUTAGES="2", TEST_RECONNECT_SECONDS="0.2")
    publish(broker, source, analysis_id)
    payload = result(broker)
    assert (payload["analysis_id"], payload["status"]) == (analysis_id, "COMPLETED")
    worker.wait("acked")
    events = events_of(worker)
    claims = [entry for entry in events if entry["event"] == "claimed"]
    assert [entry["delivery_count"] for entry in claims] == [0, 1, 2]
    assert [entry["status"] for entry in events if entry["event"] == "confirmed"] == ["COMPLETED"]
    assert broker.basic_get("analysis.results", auto_ack=True)[0] is None


def test_missing_input_fails_without_redelivery(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    worker = workers(source, S3_DOWNLOAD_MISSING="true")
    publish(broker, source, analysis_id)
    payload = result(broker)
    assert payload["status"] == "FAILED"
    assert payload["error"] == {"code": "PROCESSING_ERROR",
                                "message": "Nie udało się przetworzyć materiału. Spróbuj ponownie."}
    worker.wait("acked")
    assert len([entry for entry in events_of(worker) if entry["event"] == "claimed"]) == 1


def test_persistent_storage_outage_reaches_dlq_without_failed_result(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    worker = workers(source, S3_DOWNLOAD_OUTAGES="100", TEST_RECONNECT_SECONDS="0.2")
    publish(broker, source, analysis_id)

    def dead_letter():
        method, properties, body = broker.basic_get(f"analysis.{source}.dlq", auto_ack=True)
        return (properties, json.loads(body)) if method else None

    properties, payload = wait_for(dead_letter, timeout=60)
    assert payload["analysis_id"] == analysis_id
    assert properties.headers["x-first-death-reason"] == "delivery_limit"
    assert len([entry for entry in events_of(worker) if entry["event"] == "claimed"]) == 6
    assert broker.basic_get("analysis.results", auto_ack=True)[0] is None


def test_hung_task_exits_the_worker_and_is_redelivered(source, broker, rds, workers):
    analysis_id = str(uuid.uuid4())
    hung = workers(source, BLOCK_AT="inference", DETECTOR_TASK_HARD_LIMIT_SECONDS="2")
    publish(broker, source, analysis_id)
    hung.wait("inference")
    assert hung.process.wait(timeout=15) == 1  # Never released: the watchdog must fire.
    assert "task_hard_limit_exceeded" in (hung.directory / "worker.log").read_text()
    assert not (hung.directory / "confirmed").exists()
    replacement = workers(source)
    claimed = replacement.wait("claimed")
    assert claimed["redelivered"] is True and claimed["delivery_count"] == 1
    replacement.wait("acked")
    assert result(broker)["status"] == "COMPLETED"
