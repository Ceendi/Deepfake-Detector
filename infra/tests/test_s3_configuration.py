"""Exercise PR09 in the production init image and a disposable real SeaweedFS.

Run: python3 infra/tests/test_s3_configuration.py (Python 3.9+, Docker Compose).
No developer secrets, checkpoints, fixed host ports or existing volumes are used.
"""

from datetime import datetime, timezone
import hashlib
import hmac
import http.client
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
PREFIXES = {
    "admin": "S3_ADMIN", "file-service": "S3_FILE_SERVICE",
    "orchestrator": "S3_ORCHESTRATOR", "detector": "S3_DETECTOR",
}


def command(*args):
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
    if result.returncode:
        # Compose config / inspection / S3 logs can contain credentials. Keep
        # captured diagnostics out of unittest exception messages and CI logs.
        raise RuntimeError("Infrastructure command failed; captured output suppressed")
    return result.stdout


class S3ConfigurationTest(unittest.TestCase):
    def compose(self, *args, check=True):
        result = subprocess.run(
            ["docker", "compose", "-p", self.project, "-f", str(self.compose_file), *args],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        if check and result.returncode:
            raise RuntimeError("Isolated S3 Compose command failed; captured output suppressed")
        return result

    def write_config(self):
        # A resolved Compose model is parsed again when loaded from this file.
        # Quote dollars so values remain literal on that second pass.
        self.compose_file.write_text(json.dumps(self.config, ensure_ascii=False).replace("$", "$$"))
        self.compose_file.chmod(0o600)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="s3-configuration-")
        self.addCleanup(self.directory.cleanup)
        self.project = "s3-config-test-" + uuid.uuid4().hex[:12]
        self.compose_file = Path(self.directory.name) / "compose.json"
        model = json.loads(command(
            "docker", "compose", "-p", self.project, "--env-file", str(ROOT / ".env.example"),
            "--profile", "*", "config", "--format", "json",
        ))
        names = ("seaweedfs-init", "seaweedfs", "seaweedfs-bucket-init")
        services = {name: model["services"][name] for name in names}
        for service in services.values():
            service.pop("profiles", None)
            service["restart"] = "no"
        services["seaweedfs"]["ports"] = [
            {"target": 8333, "published": "0", "host_ip": "127.0.0.1"},
        ]
        # Runtime regressions use the actual resolved init image, mounts and
        # hardening. Override only its process and add the test script mount.
        probe = json.loads(json.dumps(services["seaweedfs-init"]))
        probe["entrypoint"] = ["python3", "-I", "/s3_renderer_cases.py"]
        probe["volumes"].append({
            "type": "bind", "source": str(ROOT / "infra/tests/s3_renderer_cases.py"),
            "target": "/s3_renderer_cases.py", "read_only": True,
        })
        services["renderer-tests"] = probe
        self.values = {}
        for identity, prefix in PREFIXES.items():
            # ASCII access keys work in the SigV4 Authorization header. The
            # runtime tests separately round-trip Unicode in access keys too.
            self.values[prefix + "_KEY"] = identity + '-key-&|\\"'
            self.values[prefix + "_SECRET"] = uuid.uuid4().hex + ' &|\\" $ Zażółć_日本_🙂 '
        services["seaweedfs-init"]["environment"] = dict(self.values)
        services["seaweedfs-bucket-init"]["environment"] = {
            name: value for name, value in self.values.items() if name.startswith("S3_ADMIN_")
        }
        self.config = {
            "name": self.project, "services": services,
            "volumes": {name: {"name": self.project + "-" + name}
                        for name in ("seaweeddata", "seaweedconfig")},
            "networks": {"deepfake": {"name": self.project + "-network"}},
        }
        self.write_config()
        # This explicit project name also applies to cleanup after setup errors.
        self.addCleanup(self.compose, "down", "--volumes", "--remove-orphans")
        self.compose("config", "--quiet")

    def signed(self, identity, method, path, body=b"", query="", secret=None):
        prefix = PREFIXES[identity]
        key = self.values[prefix + "_KEY"]
        secret = secret if secret is not None else self.values[prefix + "_SECRET"]
        now = datetime.now(timezone.utc)
        date = now.strftime("%Y%m%d")
        timestamp = now.strftime("%Y%m%dT%H%M%SZ")
        payload_hash = hashlib.sha256(body).hexdigest()
        host = "127.0.0.1:" + self.port
        canonical_path = quote(path, safe="/~-._")
        headers = {"host": host, "x-amz-content-sha256": payload_hash, "x-amz-date": timestamp}
        signed_headers = ";".join(sorted(headers))
        canonical_headers = "".join(name + ":" + headers[name] + "\n" for name in sorted(headers))
        canonical_request = "\n".join((method, canonical_path, query, canonical_headers, signed_headers, payload_hash))
        scope = date + "/us-east-1/s3/aws4_request"
        string_to_sign = "\n".join(("AWS4-HMAC-SHA256", timestamp, scope,
                                     hashlib.sha256(canonical_request.encode()).hexdigest()))
        signing_key = ("AWS4" + secret).encode("utf-8")
        for part in (date, "us-east-1", "s3", "aws4_request"):
            signing_key = hmac.new(signing_key, part.encode(), hashlib.sha256).digest()
        signature = hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
        headers["Authorization"] = (f"AWS4-HMAC-SHA256 Credential={key}/{scope}, "
                                    f"SignedHeaders={signed_headers}, Signature={signature}")
        connection = http.client.HTTPConnection("127.0.0.1", int(self.port), timeout=10)
        try:
            connection.request(method, canonical_path + ("?" + query if query else ""), body=body, headers=headers)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def assert_status(self, identity, method, path, expected, **kwargs):
        status, body = self.signed(identity, method, path, **kwargs)
        self.assertEqual(expected, status, f"unexpected S3 status for {identity} {method} {path}")
        return body

    def wait_for_buckets(self):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            result = self.compose("ps", "-a", "--format", "json").stdout
            states = [json.loads(line) for line in result.splitlines() if line.strip()]
            bucket = next((state for state in states if state["Service"] == "seaweedfs-bucket-init"), None)
            if bucket and bucket["State"] == "exited":
                self.assertEqual(0, bucket["ExitCode"], "bucket-init failed against real signed S3")
                self.port = self.compose("port", "seaweedfs", "8333").stdout.strip().rsplit(":", 1)[1]
                return
            time.sleep(1)
        self.fail("bucket-init did not complete in the isolated project")

    def assert_permissions(self):
        uploads = "/deepfake-uploads"
        artifacts = "/analysis-artifacts"
        self.assert_status("admin", "GET", "/", 200)
        self.assert_status("admin", "PUT", uploads + "/input", 200, body=b"input")
        self.assert_status("admin", "PUT", artifacts + "/artifact", 200, body=b"artifact")
        self.assert_status("admin", "GET", uploads + "/input", 200)
        self.assert_status("admin", "GET", artifacts + "/artifact", 200)
        for identity, bucket, key in (("file-service", uploads, "input"),
                                      ("orchestrator", artifacts, "artifact"),
                                      ("detector", artifacts, "artifact")):
            self.assert_status(identity, "GET", bucket, 200)
            self.assert_status(identity, "PUT", bucket + "/" + key, 200, body=identity.encode())
            body = self.assert_status(identity, "GET", bucket + "/" + key, 200)
            self.assertTrue(body == identity.encode(), "S3 object content mismatch")
        self.assert_status("detector", "GET", uploads, 200)
        self.assert_status("detector", "GET", uploads + "/input", 200)
        tags = b'<Tagging><TagSet><Tag><Key>test</Key><Value>value</Value></Tag></TagSet></Tagging>'
        for identity, bucket, key in (("file-service", uploads, "input"), ("detector", artifacts, "artifact")):
            self.assert_status(identity, "PUT", bucket + "/" + key, 200, query="tagging=", body=tags)
            self.assert_status(identity, "GET", bucket + "/" + key, 200, query="tagging=")
        for identity, bucket, key in (("file-service", artifacts, "artifact"),
                                      ("orchestrator", uploads, "input")):
            for method, path in (("GET", bucket), ("GET", bucket + "/" + key),
                                 ("PUT", bucket + "/forbidden")):
                self.assert_status(identity, method, path, 403)
        self.assert_status("detector", "PUT", uploads + "/forbidden", 403)
        for identity in ("file-service", "orchestrator", "detector"):
            self.assert_status(identity, "PUT", "/forbidden-bucket-" + identity, 403)
        # A near-miss secret must fail, proving the server authenticates the
        # exact special-character value rather than accepting unsigned calls.
        self.assert_status("admin", "GET", "/", 403, secret=self.values["S3_ADMIN_SECRET"] + "wrong")

    def test_production_image_renderer_regressions(self):
        result = self.compose("run", "--rm", "--no-deps", "renderer-tests", check=False)
        # The target tests intentionally emit only names, never actual values.
        print(result.stdout, end="", flush=True)
        print(result.stderr, end="", flush=True)
        self.assertEqual(0, result.returncode, "renderer regressions failed in the production init image")

    def test_bootstrap_restart_atomic_failure_and_real_signed_scopes(self):
        self.compose("up", "-d", "seaweedfs-bucket-init")
        self.wait_for_buckets()
        self.assert_permissions()
        # A new init process rewrites an existing config without dropping
        # identities; bucket provisioning is idempotent on persistent data.
        self.compose("stop", "seaweedfs")
        self.compose("run", "--rm", "--no-deps", "seaweedfs-init")
        self.compose("up", "-d", "--force-recreate", "seaweedfs", "seaweedfs-bucket-init")
        self.wait_for_buckets()
        self.assert_permissions()
        # Failed startup with an already-published config must leave it usable.
        self.config["services"]["seaweedfs-init"]["environment"]["S3_ADMIN_SECRET"] = ""
        self.write_config()
        result = self.compose("run", "--rm", "--no-deps", "seaweedfs-init", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("S3_ADMIN_SECRET must be set and non-empty", result.stderr)
        self.compose("restart", "seaweedfs")
        self.port = self.compose("port", "seaweedfs", "8333").stdout.strip().rsplit(":", 1)[1]
        deadline = time.monotonic() + 90
        while True:
            try:
                status, _ = self.signed("admin", "GET", "/")
                if status == 200:
                    break
            except (OSError, http.client.HTTPException):
                pass
            if time.monotonic() >= deadline:
                self.fail("previous S3 configuration was unusable after failed init and restart")
            time.sleep(1)
        self.assert_permissions()
        self.compose("run", "--rm", "--no-deps", "seaweedfs-bucket-init")
        # Inspect only the public filesystem mode, never the credential file.
        result = self.compose("exec", "-T", "seaweedfs", "stat", "-c", "%a", "/etc/seaweedfs/s3.json")
        self.assertEqual("644", result.stdout.strip())


if __name__ == "__main__":
    unittest.main(verbosity=2)
