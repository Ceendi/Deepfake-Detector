from concurrent.futures import Future
from contextvars import copy_context
from threading import Lock

import pika


class ConnectionChannel:
    """Run worker channel operations on their owning BlockingConnection thread."""

    def __init__(self, connection, channel):
        self._connection = connection
        self._channel = channel
        self._lock = Lock()
        self._closed = False
        self._pending = set()

    def close(self):
        with self._lock:
            self._closed = True
            pending = list(self._pending)
        # Release workers waiting for callbacks that this connection can no longer dispatch.
        for response in pending:
            if not response.done():
                response.set_exception(pika.exceptions.StreamLostError("Consumer connection closed"))

    def __getattr__(self, name):
        def call(*args, **kwargs):
            response = Future()
            context = copy_context()
            with self._lock:
                if self._closed:
                    raise pika.exceptions.StreamLostError("Consumer connection closed")
                self._pending.add(response)

            def invoke():
                if response.done():
                    return
                try:
                    result = context.run(getattr(self._channel, name), *args, **kwargs)
                except Exception as error:  # noqa: BLE001
                    # Any channel failure must unblock the worker waiting for this callback.
                    response.set_exception(error)
                else:
                    response.set_result(result)

            try:
                self._connection.add_callback_threadsafe(invoke)
                return response.result()
            finally:
                with self._lock:
                    self._pending.discard(response)

        return call
