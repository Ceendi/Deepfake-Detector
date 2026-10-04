"""Exercise the Compose Tempo image/config together using disposable storage.

Run from any directory with Python 3.9+ and Docker Compose:
    python3 infra/tests/test_tempo.py
"""

import json
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


class TempoIntegrationTest(unittest.TestCase):
    def command(self, *args):
        return subprocess.check_output(args, cwd=ROOT, text=True, stderr=subprocess.STDOUT)

    def compose(self, *args):
        return self.command("docker", "compose", "-f", str(self.compose_file), *args)

    def request(self, port, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = Request(
            f"http://127.0.0.1:{port}{path}",
            data=data,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        with urlopen(request, timeout=5) as response:
            return response.read().decode()

    def eventually(self, check, timeout=120):
        deadline = time.monotonic() + timeout
        last_error = None
        while time.monotonic() < deadline:
            try:
                return check()
            except (URLError, AssertionError, OSError) as error:
                last_error = error
                time.sleep(1)
        self.fail(f"Tempo did not satisfy the check: {last_error}")

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="tempo-integration-")
        self.addCleanup(self.directory.cleanup)
        self.compose_file = Path(self.directory.name) / "compose.json"
        project = "tempo-test-" + uuid.uuid4().hex[:12]
        config = json.loads(self.command(
            "docker", "compose", "--env-file", str(ROOT / ".env.example"),
            "--profile", "*", "config", "--format", "json",
        ))
        tempo = config["services"]["tempo"]
        tempo.pop("profiles", None)
        tempo["restart"] = "no"
        tempo["ports"] = [
            {"target": port, "published": "0", "host_ip": "127.0.0.1"}
            for port in (3200, 4318)
        ]
        # Compose's resolved names refer to the developer's volumes/network.
        # Give every test its own names before starting or deleting anything.
        config = {
            "name": project,
            "services": {"tempo": tempo},
            "volumes": {"tempo-data": {"name": project + "-data"}},
            "networks": {"deepfake": {"name": project + "-network"}},
        }
        self.compose_file.write_text(json.dumps(config))
        self.addCleanup(self.compose, "down", "--volumes", "--remove-orphans")
        self.addCleanup(self.print_failure_logs)
        self.compose("up", "-d")
        self.http_port = self.compose("port", "tempo", "3200").strip().rsplit(":", 1)[1]
        self.otlp_port = self.compose("port", "tempo", "4318").strip().rsplit(":", 1)[1]
        self.eventually(lambda: self.assertIn("ready", self.request(self.http_port, "/ready")))

    def print_failure_logs(self):
        outcome = self._outcome.result
        if any(test is self for test, _ in outcome.failures + outcome.errors):
            print(self.compose("logs", "--tail", "100", "tempo"))

    def send_trace(self):
        trace_id = uuid.uuid4().hex
        span_id = uuid.uuid4().hex[:16]
        now = time.time_ns()
        payload = {"resourceSpans": [{
            "resource": {"attributes": [{
                "key": "service.name", "value": {"stringValue": "tempo-regression"},
            }]},
            "scopeSpans": [{"scope": {"name": "compose-smoke"}, "spans": [{
                "traceId": trace_id, "spanId": span_id, "name": "persisted-smoke-span",
                "kind": 1, "startTimeUnixNano": str(now),
                "endTimeUnixNano": str(now + 1_000_000),
            }]}],
        }]}
        response = json.loads(self.request(self.otlp_port, "/v1/traces", payload))
        self.assertFalse(response.get("partialSuccess", {}).get("rejectedSpans", 0))

        return trace_id

    def test_otlp_trace_survives_restart_with_preserved_retention(self):
        config = self.request(self.http_port, "/status/config")
        self.assertRegex(config, r"block_retention: (72h(?:0m0s)?|3d)")
        self.assertIn("/var/tempo/blocks", config)
        self.assertIn("/var/tempo/live-store/traces", config)
        self.assertRegex(config, r"max_block_duration: 5m(?:0s)?")
        self.assertNotRegex(config, r"(?m)^ingester:|^compactor:")

        trace_id = self.send_trace()

        def assert_trace():
            trace = self.request(self.http_port, f"/api/traces/{trace_id}")
            self.assertIn("persisted-smoke-span", trace)

        self.eventually(assert_trace)
        # Allow the live store to move the idle trace to its persistent WAL.
        time.sleep(15)
        self.compose("stop", "--timeout", "30", "tempo")
        self.compose("start", "tempo")
        # Docker may allocate a different ephemeral host port after a restart.
        self.http_port = self.compose("port", "tempo", "3200").strip().rsplit(":", 1)[1]
        self.eventually(lambda: self.assertIn("ready", self.request(self.http_port, "/ready")))
        self.eventually(assert_trace)
        container = self.compose("ps", "-q", "tempo").strip()
        state = json.loads(self.command("docker", "inspect", container))[0]
        self.assertEqual(0, state["RestartCount"])

    def test_tempo2_flushed_blocks_remain_readable(self):
        # The prior Compose version is intentionally fixed as the migration baseline.
        config = json.loads(self.compose_file.read_text())
        upgraded_config = self.compose_file.read_text()
        legacy_config = Path(self.directory.name) / "tempo2.yaml"
        legacy_config.write_text("""server:
  http_listen_port: 3200
distributor:
  receivers:
    otlp:
      protocols:
        http:
          endpoint: 0.0.0.0:4318
ingester:
  max_block_duration: 5m
  flush_all_on_shutdown: true
compactor:
  compaction:
    block_retention: 72h
storage:
  trace:
    backend: local
    local:
      path: /var/tempo/blocks
    wal:
      path: /var/tempo/wal
""")
        config["services"]["tempo"]["image"] = "grafana/tempo:2.10.6"
        for mount in config["services"]["tempo"]["volumes"]:
            if mount["target"] == "/etc/tempo/tempo.yaml":
                mount["source"] = str(legacy_config)
        self.compose_file.write_text(json.dumps(config))
        self.compose("up", "-d", "--force-recreate")
        self.http_port = self.compose("port", "tempo", "3200").strip().rsplit(":", 1)[1]
        self.otlp_port = self.compose("port", "tempo", "4318").strip().rsplit(":", 1)[1]
        self.eventually(lambda: self.assertIn("ready", self.request(self.http_port, "/ready")))
        trace_id = self.send_trace()
        # Drain the old ingester to backend blocks, not just its version-specific WAL.
        self.compose("stop", "--timeout", "90", "tempo")
        self.compose_file.write_text(upgraded_config)
        self.compose("up", "-d", "--force-recreate")
        self.http_port = self.compose("port", "tempo", "3200").strip().rsplit(":", 1)[1]
        self.eventually(lambda: self.assertIn("ready", self.request(self.http_port, "/ready")))
        self.eventually(lambda: self.assertIn(
            "persisted-smoke-span", self.request(self.http_port, f"/api/traces/{trace_id}"),
        ))


if __name__ == "__main__":
    unittest.main(verbosity=2)
