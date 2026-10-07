"""Regressions executed inside the exact Compose seaweedfs-init image."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("renderer", "/render_config.py")
RENDERER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RENDERER)
PREFIXES = {
    "admin": "S3_ADMIN", "file-service": "S3_FILE_SERVICE",
    "orchestrator": "S3_ORCHESTRATOR", "detector": "S3_DETECTOR",
}
ACTIONS = {
    "admin": ["Admin"],
    "file-service": ["Read:deepfake-uploads", "Write:deepfake-uploads",
                     "List:deepfake-uploads", "Tagging:deepfake-uploads"],
    "orchestrator": ["Read:analysis-artifacts", "Write:analysis-artifacts", "List:analysis-artifacts"],
    "detector": ["Read:deepfake-uploads", "List:deepfake-uploads", "Read:analysis-artifacts",
                 "Write:analysis-artifacts", "List:analysis-artifacts", "Tagging:analysis-artifacts"],
}


def credentials(fragment="baseline"):
    return {f"{prefix}_{suffix}": f"{name}-{suffix}-{fragment}"
            for name, prefix in PREFIXES.items() for suffix in ("KEY", "SECRET")}


class RendererTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="s3-renderer-")
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name) / "s3.json"
        self.template = Path("/s3.json.tmpl")

    def run_init(self, values, template=None):
        return subprocess.run(
            ["/bin/sh", "/init.sh", "--output", str(self.output),
             "--template", str(template or self.template)],
            env={**os.environ, **values}, capture_output=True, text=True, check=False,
        )

    def assert_success(self, values):
        result = self.run_init(values)
        self.assertEqual(0, result.returncode, "init failed in the deployment image")
        document = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertEqual({"identities"}, set(document))
        self.assertEqual(list(PREFIXES), [identity["name"] for identity in document["identities"]])
        for identity in document["identities"]:
            name = identity["name"]
            self.assertEqual({"name", "credentials", "actions"}, set(identity))
            self.assertEqual(ACTIONS[name], identity["actions"])
            expected = [{"accessKey": values[PREFIXES[name] + "_KEY"],
                         "secretKey": values[PREFIXES[name] + "_SECRET"]}]
            # unittest's ordinary equality diff would print credential values.
            self.assertTrue(expected == identity["credentials"], "credential round-trip mismatch")
        self.assertEqual(0o644, self.output.stat().st_mode & 0o777)
        self.assertFalse(list(self.output.parent.glob(".s3.json-*")))
        logs = result.stdout + result.stderr
        self.assertTrue(all(value not in logs for value in values.values()), "init disclosed a credential")

    def assert_failure_preserves(self, values, template=None):
        previous = self.output.read_bytes()
        previous_stat = self.output.stat()
        result = self.run_init(values, template)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ERROR: SeaweedFS S3 configuration", result.stderr)
        self.assertTrue(previous == self.output.read_bytes(), "failed init replaced the previous configuration")
        self.assertEqual(previous_stat.st_ino, self.output.stat().st_ino)
        self.assertEqual(0o644, self.output.stat().st_mode & 0o777)
        self.assertFalse(list(self.output.parent.glob(".s3.json-*")))
        logs = result.stdout + result.stderr
        self.assertTrue(all(value not in logs for value in values.values() if value), "init disclosed a credential")

    def test_round_trip_special_characters_and_combinations(self):
        for fragment in ("&", "|", "\\", '"', " spaced value ", "Zażółć_日本_🙂",
                         ' &|\\" Zażółć_日本_🙂 ', "${S3_ADMIN_KEY}", "${S3_DETECTOR_SECRET}"):
            # Labels remain safe even if a case fails.
            with self.subTest(case="credential encoding"):
                self.assert_success(credentials(fragment))

    def test_missing_empty_controls_invalid_utf8_and_duplicate_access_keys(self):
        baseline = credentials()
        self.assert_success(baseline)
        for name in baseline:
            for value in ("", "invalid\nvalue", "invalid\rvalue", "invalid\tvalue", "invalid\x7fvalue", "invalid\udcffvalue"):
                with self.subTest(variable=name, kind="invalid input"):
                    self.assert_failure_preserves({**baseline, name: value})
            # Remove it from the entire child environment, including Compose.
            with patch.dict(os.environ, {"PATH": os.environ["PATH"]}, clear=True):
                missing = {key: value for key, value in baseline.items() if key != name}
                self.assert_failure_preserves(missing)
        duplicate = {**baseline, "S3_DETECTOR_KEY": baseline["S3_ADMIN_KEY"]}
        self.assert_failure_preserves(duplicate)

    def test_bad_templates_and_missing_template_never_publish(self):
        values = credentials()
        template_text = self.template.read_text(encoding="utf-8")
        corrupt = Path(self.directory.name) / "template.json"
        for text in ('{"secret": "unclosed', '{"identities": [], "identities": []}',
                     '{"identities": NaN}', '[]', '{"identities": []}',
                     template_text.replace("${S3_ADMIN_KEY}", "${UNKNOWN}"),
                     template_text.replace('"Admin"', 'null'),
                     template_text.replace('"detector"', '"admin"')):
            self.assert_success(values)
            corrupt.write_text(text, encoding="utf-8")
            self.assert_failure_preserves(values, corrupt)
        corrupt.unlink()
        self.assert_failure_preserves(values, corrupt)

    def test_empty_bootstrap_failure_leaves_no_destination(self):
        result = self.run_init({**credentials(), "S3_ADMIN_SECRET": ""})
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.output.parent.glob(".s3.json-*")))

    def test_atomic_publication_and_validation_write_rename_failures(self):
        values = credentials()
        self.assert_success(values)
        document = RENDERER.render(self.template.read_text(encoding="utf-8"), credentials("replacement"))
        previous = self.output.read_bytes()
        for target, failure in (("load_json", RENDERER.ConfigurationError("validation failed")),
                                ("os.fsync", OSError("write failed")),
                                ("os.replace", OSError("rename failed"))):
            with patch("renderer." + target, side_effect=failure):
                with self.assertRaises((RENDERER.ConfigurationError, OSError)):
                    RENDERER.publish(document, self.output)
            self.assertTrue(previous == self.output.read_bytes(), "publication failure replaced configuration")
            self.assertFalse(list(self.output.parent.glob(".s3.json-*")))
        real_replace = os.replace

        def checked_replace(source, destination):
            self.assertTrue(previous == destination.read_bytes(), "destination changed before atomic replace")
            self.assertTrue(json.loads(source.read_text()) == document, "unvalidated file at publication")
            self.assertEqual(0o644, source.stat().st_mode & 0o777)
            real_replace(source, destination)

        with patch.object(RENDERER.os, "replace", side_effect=checked_replace):
            RENDERER.publish(document, self.output)
        self.assertTrue(json.loads(self.output.read_text()) == document, "atomic publication failed")


if __name__ == "__main__":
    import sys
    sys.modules["renderer"] = RENDERER
    unittest.main(verbosity=2)
