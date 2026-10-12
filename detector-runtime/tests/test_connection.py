from unittest.mock import MagicMock, call

import pika
import pytest

from detector_runtime import consumer as runtime_module


class StopConsumer(BaseException):
    pass


def test_topology_matches_the_existing_contract(runtime):
    ch = MagicMock()
    runtime.declare_topology(ch)
    assert ch.exchange_declare.call_args_list == [
        call(exchange="analysis.exchange", exchange_type="topic", durable=True),
        call(exchange="analysis.dlx", exchange_type="direct", durable=True),
    ]
    assert ch.queue_declare.call_args_list == [
        call(queue=runtime.queue, durable=True, arguments={
            "x-dead-letter-exchange": "analysis.dlx",
            "x-dead-letter-routing-key": f"{runtime.queue}.dlq",
        }),
        call(queue=f"{runtime.queue}.dlq", durable=True),
        call(queue="analysis.results", durable=True),
        call(queue="analysis.progress", durable=True),
    ]
    assert ch.queue_bind.call_args_list == [
        call(queue=runtime.queue, exchange="analysis.exchange", routing_key=runtime.queue),
        call(queue=f"{runtime.queue}.dlq", exchange="analysis.dlx",
             routing_key=f"{runtime.queue}.dlq"),
        call(queue="analysis.results", exchange="analysis.exchange", routing_key="analysis.results"),
        call(queue="analysis.progress", exchange="analysis.exchange", routing_key="analysis.progress"),
    ]


@pytest.mark.parametrize("close_fails", [False, True])
def test_publication_failure_closes_connection_before_reconnect(runtime, monkeypatch, close_fails):
    conn = MagicMock()
    ch = conn.channel.return_value
    monkeypatch.setattr(runtime_module.pika, "BlockingConnection", MagicMock(return_value=conn))
    if close_fails:
        conn.close.side_effect = pika.exceptions.StreamLostError("lost during close")
    conn.process_data_events.side_effect = pika.exceptions.UnroutableError([])

    def backoff(seconds):
        assert seconds == 5
        assert runtime.health_state["ok"] is False
        conn.close.assert_called_once_with()
        raise StopConsumer()

    monkeypatch.setattr(runtime_module.time, "sleep", backoff)
    with pytest.raises(StopConsumer):
        runtime.run_consumer()
    ch.basic_qos.assert_called_once_with(prefetch_count=1)
    ch.confirm_delivery.assert_called_once_with()
    params = runtime_module.pika.BlockingConnection.call_args.args[0]
    assert params.heartbeat == 30
    assert params.blocked_connection_timeout == 300
    if close_fails:
        runtime.log.warning.assert_called_once_with("consumer_connection_close_failed")


def test_idle_consumer_updates_shared_health_state(runtime, monkeypatch):
    conn = MagicMock()
    ch = conn.channel.return_value
    monkeypatch.setattr(runtime_module.pika, "BlockingConnection", MagicMock(return_value=conn))
    monkeypatch.setattr(runtime_module.time, "time", MagicMock(side_effect=[10.0, 15.0]))

    def idle_events(*args, **kwargs):
        if conn.process_data_events.call_count == 1:
            assert runtime.health_state == {"ok": True, "last_beat": 10.0}
        else:
            assert runtime.health_state == {"ok": True, "last_beat": 15.0}
            raise StopConsumer()

    conn.process_data_events.side_effect = idle_events
    with pytest.raises(StopConsumer):
        runtime.run_consumer()
    assert ch.basic_consume.call_args.kwargs["queue"] == runtime.queue
    assert ch.basic_consume.call_args.kwargs["auto_ack"] is False
    conn.process_data_events.assert_called_with(time_limit=1)
    assert runtime.health_state["ok"] is False
