"""Regression guards for required checks and the image publication boundary."""

import importlib.util
import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def jobs(name):
    source = (ROOT / f'.github/workflows/{name}.yml').read_text()
    return dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)',
                           source.split('\njobs:\n', 1)[1]))


class CiGatesTest(unittest.TestCase):
    def test_every_workflow_gate_waits_for_every_job_and_rejects_failure_or_cancel(self):
        for workflow, gate in (('ci', 'ci-ok'), ('e2e', 'e2e-ok'),
                               ('infra-smoke', 'infra-ok'), ('security-scan', 'security-ok'),
                               ('build-push', 'images-ok')):
            with self.subTest(workflow=workflow):
                blocks = jobs(workflow)
                self.assertIn(gate, blocks)
                block = blocks[gate]
                self.assertIn('if: always()', block)
                needs = re.search(r'needs: \[([^\]]+)\]', block).group(1)
                self.assertEqual(set(blocks) - {gate}, set(needs.replace(' ', '').split(',')))
                self.assertIn('toJSON(needs)', block)
                self.assertIn('infra/ci/check_jobs.py', block)

    def test_required_workflows_have_no_trigger_path_filter(self):
        for workflow in ('ci', 'e2e', 'infra-smoke', 'security-scan', 'build-push'):
            source = (ROOT / f'.github/workflows/{workflow}.yml').read_text()
            self.assertNotIn('paths:', source.split('\npermissions:', 1)[0])

    def test_static_guards_and_title_lint_are_unconditionally_in_ci(self):
        blocks = jobs('ci')
        self.assertIn('static-guards', blocks)
        self.assertNotIn('\n    if:', blocks['static-guards'])
        self.assertIn('infra/tests/run_static.py', blocks['static-guards'])
        self.assertIn('commitlint', blocks)
        self.assertIn('github.event.pull_request.title', blocks['commitlint'])
        self.assertIn('--strict', blocks['commitlint'])
        self.assertIn('fail_on_failure: true', blocks['test-report'])
        self.assertIn('FORK_PR:', blocks['ci-ok'])

    def test_dependency_scan_and_e2e_run_after_selection_or_java_failure(self):
        self.assertIn("if: ${{ !cancelled() }}", jobs('ci')['dependencies'])
        self.assertIn("!cancelled() && needs.changes.outputs.app != 'false'", jobs('e2e')['playwright'])

    def test_job_results_policy(self):
        script = ROOT / 'infra/ci/check_jobs.py'
        for result, code in (('success', 0), ('skipped', 0), ('failure', 1), ('cancelled', 1)):
            with self.subTest(result=result):
                process = subprocess.run([sys.executable, str(script)], input=json.dumps({
                    'test': {'result': result}}), text=True, capture_output=True, check=False)
                self.assertEqual(code, process.returncode, process.stderr)

    def test_fork_sonar_omission_is_explicit(self):
        import os

        process = subprocess.run([sys.executable, str(ROOT / 'infra/ci/check_jobs.py')],
                                 input=json.dumps({'sonar': {'result': 'skipped'}}),
                                 env={**os.environ, 'FORK_PR': 'true'}, text=True,
                                 capture_output=True, check=False)
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertIn('Sonar', process.stdout)
        self.assertIn('fork', process.stdout)


class PublishingTest(unittest.TestCase):
    def test_scanned_archive_can_be_uploaded_again_in_a_rerun(self):
        block = jobs('build-push')['image'].split('name: scanned-', 1)[1]
        self.assertIn('overwrite: true', block)

    def test_publication_reuses_the_scanned_image_and_defers_privileges(self):
        blocks = jobs('build-push')
        self.assertNotIn('packages: write', blocks['image'])
        self.assertNotIn('id-token: write', blocks['image'])
        self.assertEqual(1, blocks['image'].count('docker/build-push-action@'))
        self.assertIn('docker save', blocks['image'])
        self.assertIn('docker load', blocks['publish'])
        self.assertIn('publishing.py push', blocks['publish'])
        self.assertIn('needs: [select, image]', blocks['publish'])
        self.assertIn("github.event_name != 'pull_request'", blocks['publish'])
        self.assertIn('needs: [select, image, publish, attest]', blocks['promote'])
        self.assertIn('needs: [select, image, publish]', blocks['attest'])
        self.assertIn('actions/attest@', blocks['attest'])
        self.assertIn('imagetools', (ROOT / 'infra/ci/publishing.py').read_text())

    def test_verified_commit_and_immutable_tags(self):
        spec = importlib.util.spec_from_file_location('publishing', ROOT / 'infra/ci/publishing.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        sha = 'a' * 40
        green = {'headSha': sha, 'conclusion': 'success', 'event': 'push', 'headBranch': 'main'}
        self.assertEqual(sha, module.green_sha([green]))
        for bad in ({**green, 'event': 'pull_request'}, {**green, 'conclusion': 'failure'},
                    {**green, 'headBranch': 'other'}):
            with self.assertRaises(ValueError):
                module.green_sha([bad])
        self.assertEqual('sha-aaaaaaa-r42-a2', module.run_tag(sha, '42', '2'))
        self.assertFalse(module.is_current_main(sha, 'b' * 40))
        self.assertTrue(module.is_current_main(sha, sha))


if __name__ == '__main__':
    unittest.main(verbosity=2)
