"""Create the least-privilege AMQP users through the management API.

Idempotent: every run (re)sets passwords, tags and permissions, so it also migrates an
existing broker volume and rotates passwords. The default administrator from
RABBITMQ_DEFAULT_USER is left untouched and is meant for manual operations only.
Topology and permissions follow docs/contracts/amqp-messages.md.
"""

import base64
import json
import os
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

VHOST = "/"
TOPIC_EXCHANGE = "analysis.exchange"
DLX = "analysis.dlx"
# Declaring a NEW queue with x-dead-letter-exchange also needs read on the queue and write on the
# DLX, which neither user should have; pre-declaring with identical arguments avoids both grants.
TASK_QUEUES = ("analysis.video", "analysis.audio")


def names(*items):
    return "^(" + "|".join(item.replace(".", r"\.") for item in items) + ")$"


# configure = declare/delete, write = publish to an exchange or bind a queue,
# read = consume from a queue or bind from an exchange. Topic permissions restrict
# routing keys on the topic exchange (write = publish, read = bind).
USERS = {
    "orchestrator": {
        "password": "RABBITMQ_ORCHESTRATOR_PASSWORD",
        "configure": names("analysis.exchange", "analysis.dlx", "analysis.video", "analysis.audio",
                           "analysis.results", "analysis.progress", "analysis.video.dlq",
                           "analysis.audio.dlq", "analysis.results.dlq", "analysis.backend.quarantine"),
        "write": names("analysis.exchange", "analysis.dlx", "analysis.video", "analysis.audio",
                       "analysis.results", "analysis.progress", "analysis.video.dlq",
                       "analysis.audio.dlq", "analysis.results.dlq", "analysis.backend.quarantine"),
        "read": names("analysis.exchange", "analysis.dlx", "analysis.results", "analysis.progress",
                      "analysis.video.dlq", "analysis.audio.dlq", "analysis.results.dlq"),
        "topic_write": names("analysis.video", "analysis.audio"),
        "topic_read": names("analysis.video", "analysis.audio", "analysis.results", "analysis.progress"),
    },
    "detector": {
        "password": "RABBITMQ_DETECTOR_PASSWORD",
        "configure": names("analysis.exchange", "analysis.dlx", "analysis.video", "analysis.audio",
                           "analysis.video.dlq", "analysis.audio.dlq", "analysis.results",
                           "analysis.progress"),
        "write": names("analysis.exchange", "analysis.video", "analysis.audio", "analysis.video.dlq",
                       "analysis.audio.dlq", "analysis.results", "analysis.progress"),
        "read": names("analysis.exchange", "analysis.dlx", "analysis.video", "analysis.audio"),
        "topic_write": names("analysis.results", "analysis.progress"),
        "topic_read": names("analysis.video", "analysis.audio", "analysis.results", "analysis.progress"),
    },
}


class ProvisioningError(Exception):
    """A safe error message that never includes credential values."""


def required(environment, name):
    value = environment.get(name)
    if not value:
        raise ProvisioningError(f"{name} must be set and non-empty")
    # AMQP PLAIN separates fields with NUL; other controls are always a typo.
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ProvisioningError(f"{name} must not contain control characters")
    return value


class Management:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/")
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self.headers = {"Authorization": "Basic " + token, "Content-Type": "application/json"}

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = Request(self.url + "/api" + path, data=data, method=method, headers=self.headers)
        with urlopen(request, timeout=10) as response:
            payload = response.read()
            return json.loads(payload) if payload else None

    def wait_for_administrator(self, timeout):
        deadline = time.monotonic() + timeout
        while True:
            try:
                identity = self.call("GET", "/whoami")
                break
            except HTTPError as error:
                if error.code in (401, 403):
                    raise ProvisioningError("RabbitMQ rejected the administrator credentials") from None
                if error.code < 500 or time.monotonic() >= deadline:
                    raise ProvisioningError(f"RabbitMQ management API returned HTTP {error.code}") from None
            except (URLError, OSError):
                if time.monotonic() >= deadline:
                    raise ProvisioningError("RabbitMQ management API is unreachable") from None
            time.sleep(1)
        tags = identity.get("tags") or []
        if isinstance(tags, str):
            tags = tags.split(",")
        if "administrator" not in tags:
            raise ProvisioningError("RABBITMQ_ADMIN_USER is not a RabbitMQ administrator")


def provision(environment, timeout=90):
    admin = required(environment, "RABBITMQ_ADMIN_USER")
    admin_password = required(environment, "RABBITMQ_ADMIN_PASSWORD")
    if admin in USERS:
        # Provisioning would strip the administrator tag from the only manual account.
        raise ProvisioningError(f"RABBITMQ_ADMIN_USER must not be one of: {', '.join(USERS)}")
    passwords = {user: required(environment, spec["password"]) for user, spec in USERS.items()}
    management = Management(environment.get("RABBITMQ_MANAGEMENT_URL", "http://rabbitmq:15672"),
                            admin, admin_password)
    management.wait_for_administrator(timeout)
    vhost = quote(VHOST, safe="")
    try:
        # Topic permissions need an existing exchange. Arguments match the application
        # declarations (docs/contracts/amqp-messages.md), so this is a no-op on an existing broker.
        for exchange, kind in ((TOPIC_EXCHANGE, "topic"), (DLX, "direct")):
            management.call("PUT", f"/exchanges/{vhost}/{quote(exchange, safe='')}", {
                "type": kind, "durable": True, "auto_delete": False, "internal": False, "arguments": {}})
        for queue in TASK_QUEUES:
            management.call("PUT", f"/queues/{vhost}/{quote(queue, safe='')}", {
                "durable": True, "auto_delete": False, "arguments": {
                    "x-queue-type": "quorum", "x-delivery-limit": 5,
                    "x-dead-letter-exchange": DLX, "x-dead-letter-routing-key": queue + ".dlq"}})
        for user, spec in USERS.items():
            # No tags: application users get no management UI or HTTP API access.
            management.call("PUT", f"/users/{user}", {"password": passwords[user], "tags": ""})
            management.call("PUT", f"/permissions/{vhost}/{user}", {
                "configure": spec["configure"], "write": spec["write"], "read": spec["read"]})
            management.call("PUT", f"/topic-permissions/{vhost}/{user}", {
                "exchange": TOPIC_EXCHANGE, "write": spec["topic_write"], "read": spec["topic_read"]})
    except HTTPError as error:
        raise ProvisioningError(f"RabbitMQ management API returned HTTP {error.code}") from None
    except (URLError, OSError):
        raise ProvisioningError("RabbitMQ management API became unreachable") from None


def main():
    try:
        provision(os.environ)
    except ProvisioningError as error:
        print(f"ERROR: RabbitMQ user provisioning: {error}", file=sys.stderr)
        return 1
    print("RabbitMQ users provisioned: " + ", ".join(USERS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
