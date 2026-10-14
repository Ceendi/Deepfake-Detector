"""Least-privilege broker users: the real runtime works as `detector`, and both users are denied
everything outside their own queues and routing keys (provisioned by run.sh with
infra/rabbitmq/provision_users.py)."""
import json
import os
import subprocess
import sys

import pika
import pytest
from detector_runtime.consumer import DetectorRuntime
from support import ROOT, connect, declare

DLX = "analysis.dlx"
ORCHESTRATOR_QUEUES = {
    # Mirrors RabbitConfig: quorum task queues with broker dead-lettering, classic for the rest.
    "analysis.video": {"x-queue-type": "quorum", "x-delivery-limit": 5,
                       "x-dead-letter-exchange": DLX, "x-dead-letter-routing-key": "analysis.video.dlq"},
    "analysis.audio": {"x-queue-type": "quorum", "x-delivery-limit": 5,
                       "x-dead-letter-exchange": DLX, "x-dead-letter-routing-key": "analysis.audio.dlq"},
    "analysis.results": None, "analysis.progress": None, "analysis.video.dlq": None,
    "analysis.audio.dlq": None, "analysis.results.dlq": None, "analysis.backend.quarantine": None,
}
BINDINGS = [("analysis.exchange", queue) for queue in
            ("analysis.video", "analysis.audio", "analysis.results", "analysis.progress")] + \
           [(DLX, queue) for queue in ("analysis.video.dlq", "analysis.audio.dlq", "analysis.results.dlq",
                                       "analysis.backend.quarantine")]


def user(name):
    if name == "detector":
        return connect("detector", os.environ["RABBITMQ_DETECTOR_PASSWORD"])
    return connect("orchestrator", os.environ["RABBITMQ_ORCHESTRATOR_PASSWORD"])


def message(**fields):
    return json.dumps({"analysis_id": "00000000-0000-0000-0000-000000000000", **fields}).encode()


def publish(channel, exchange, routing_key):
    channel.basic_publish(exchange=exchange, routing_key=routing_key, body=message(),
                          properties=pika.BasicProperties(delivery_mode=2), mandatory=True)


def assert_refused(connection, action):
    channel = connection.channel()
    channel.confirm_delivery()
    with pytest.raises(pika.exceptions.ChannelClosedByBroker) as refused:
        action(channel)
    assert refused.value.reply_code == 403, refused.value


@pytest.fixture
def admin():
    connection = connect()
    channel = connection.channel()
    declare(channel)
    for queue in ("analysis.results", "analysis.progress"):
        channel.queue_purge(queue)
    yield channel
    channel = connection.channel() if channel.is_closed else channel
    for queue in ("analysis.results", "analysis.progress"):
        channel.queue_purge(queue)
    # Queues only the orchestrator declares are not part of the worker fixtures.
    for queue in ("analysis.results.dlq", "analysis.backend.quarantine"):
        channel.queue_delete(queue)
    connection.close()


def test_detector_runs_real_topology_and_publication_and_nothing_else(admin):
    connection = user("detector")
    try:
        channel = connection.channel()
        channel.confirm_delivery()
        for source in ("video", "audio"):
            runtime = DetectorRuntime(source=source, queue=f"analysis.{source}", log=None, tracer=None,
                                      s3_client=None, redis_client=None, process_task=None,
                                      record_outcome=None)
            runtime.declare_topology(channel)
            runtime.publish(channel, "analysis.progress", {"analysis_id": source, "progress": 1})
            runtime.publish(channel, "analysis.results", {"analysis_id": source, "status": "FAILED"})
            channel.basic_consume(queue=f"analysis.{source}", on_message_callback=lambda *args: None)
        assert admin.queue_declare("analysis.results", passive=True).method.message_count == 2
        assert admin.queue_declare("analysis.progress", passive=True).method.message_count == 2

        # Task injection, result theft and backend queue tampering are refused.
        for source in ("video", "audio"):
            assert_refused(connection, lambda ch, s=source: publish(ch, "analysis.exchange", f"analysis.{s}"))
        assert_refused(connection, lambda ch: publish(ch, "", "analysis.results"))
        assert_refused(connection, lambda ch: publish(ch, DLX, "analysis.results.dlq"))
        assert_refused(connection, lambda ch: publish(ch, "amq.topic", "analysis.results"))
        for queue in ("analysis.results", "analysis.progress", "analysis.video.dlq", "analysis.audio.dlq"):
            assert_refused(connection, lambda ch, q=queue: ch.basic_get(q))
        for queue in ("analysis.results.dlq", "analysis.backend.quarantine", "rogue.queue"):
            assert_refused(connection, lambda ch, q=queue: ch.queue_declare(q, durable=True))
        assert_refused(connection, lambda ch: ch.queue_bind("analysis.video", "analysis.exchange", "#"))
        assert_refused(connection, lambda ch: ch.queue_purge("analysis.video.dlq"))
        assert_refused(connection, lambda ch: ch.exchange_declare("rogue.exchange", "topic"))
    finally:
        connection.close()


def test_orchestrator_declares_backend_topology_and_publishes_only_tasks(admin):
    admin.queue_purge("analysis.video")
    admin.queue_purge("analysis.audio")
    connection = user("orchestrator")
    try:
        channel = connection.channel()
        channel.confirm_delivery()
        channel.exchange_declare("analysis.exchange", "topic", durable=True)
        channel.exchange_declare(DLX, "direct", durable=True)
        for queue, arguments in ORCHESTRATOR_QUEUES.items():
            channel.queue_declare(queue, durable=True, arguments=arguments)
        for exchange, queue in BINDINGS:
            channel.queue_bind(queue, exchange, routing_key=queue)
        publish(channel, "analysis.exchange", "analysis.video")
        publish(channel, "analysis.exchange", "analysis.audio")
        # The confirmed recoverer republishes to the dead-letter exchange.
        publish(channel, DLX, "analysis.backend.quarantine")
        publish(channel, DLX, "analysis.results.dlq")
        for queue in ("analysis.results", "analysis.progress", "analysis.video.dlq",
                      "analysis.audio.dlq", "analysis.results.dlq"):
            channel.basic_consume(queue=queue, on_message_callback=lambda *args: None)
        for queue in ("analysis.video", "analysis.audio"):
            method, _, _ = admin.basic_get(queue, auto_ack=True)
            assert method is not None, queue

        # Forged detector results and reading task queues are refused.
        for routing_key in ("analysis.results", "analysis.progress"):
            assert_refused(connection, lambda ch, key=routing_key: publish(ch, "analysis.exchange", key))
        assert_refused(connection, lambda ch: publish(ch, "", "analysis.video"))
        for queue in ("analysis.video", "analysis.audio", "analysis.backend.quarantine"):
            assert_refused(connection, lambda ch, q=queue: ch.basic_get(q))
        assert_refused(connection, lambda ch: ch.queue_declare("rogue.queue", durable=True))
    finally:
        connection.close()


def test_users_declare_their_topology_on_a_freshly_provisioned_broker(admin):
    # Only rabbitmq-init has run: new quorum queues with a DLX need read on the queue and write on
    # the DLX, which the users lack, so the init must have pre-declared them with equal arguments.
    for queue in ORCHESTRATOR_QUEUES:
        admin.queue_delete(queue)
    for exchange in ("analysis.exchange", DLX):
        admin.exchange_delete(exchange)
    subprocess.run([sys.executable, "-I", str(ROOT / "infra/rabbitmq/provision_users.py")], check=True)
    for name in ("detector", "orchestrator"):
        connection = user(name)
        try:
            channel = connection.channel()
            if name == "detector":
                for source in ("video", "audio"):
                    DetectorRuntime(source=source, queue=f"analysis.{source}", log=None, tracer=None,
                                    s3_client=None, redis_client=None, process_task=None,
                                    record_outcome=None).declare_topology(channel)
            else:
                for queue, arguments in ORCHESTRATOR_QUEUES.items():
                    channel.queue_declare(queue, durable=True, arguments=arguments)
                for exchange, queue in BINDINGS:
                    channel.queue_bind(queue, exchange, routing_key=queue)
        finally:
            connection.close()
