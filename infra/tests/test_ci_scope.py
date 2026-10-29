"""Guard the pull request job selection in ci.yml (infra/ci/ci_scope.py)."""

import json
import re
import sys
import unittest

from test_discovery import ROOT

sys.path.insert(0, str(ROOT / 'infra/ci'))
from ci_scope import EVERYTHING, outputs, scope


def run(*paths):
    return outputs(scope(paths))


class CiScopeTest(unittest.TestCase):
    def test_rename_out_of_a_tested_tree_includes_the_old_path(self):
        files = [{'filename': 'docs/legacy.ts', 'previous_filename': 'frontend/src/legacy.ts'}]
        paths = [item[key] for item in files for key in ('filename', 'previous_filename')
                 if key in item]
        self.assertEqual('true', run(*paths)['frontend'])
        for name in ('ci', 'e2e'):
            workflow = (ROOT / f'.github/workflows/{name}.yml').read_text()
            self.assertIn("--jq '.[] | .filename, (.previous_filename // empty)'", workflow)

    def test_frontend_change_skips_java_tests_and_detectors(self):
        self.assertEqual({'java': '[]', 'frontend': 'true', 'detectors': 'false'},
                         run('frontend/src/pages/Playground/Playground.module.css'))

    def test_java_change_verifies_only_that_service(self):
        self.assertEqual(['gateway'], json.loads(run('gateway/pom.xml')['java']))
        self.assertEqual('false', run('gateway/pom.xml')['frontend'])

    def test_detector_change_also_runs_the_orchestrator_worker_tests(self):
        for path in ('audio-detector/src/inference.py', 'detector-runtime/detector_runtime/consumer.py',
                     'tests/worker_redelivery/worker.py'):
            with self.subTest(path=path):
                result = run(path)
                self.assertEqual('true', result['detectors'])
                self.assertEqual(['orchestrator'], json.loads(result['java']))

    def test_shared_contract_fixtures_run_every_consumer(self):
        result = run('orchestrator/src/test/resources/contracts/audio-score-results.json')
        self.assertEqual(('true', 'true', ['orchestrator']),
                         (result['frontend'], result['detectors'], json.loads(result['java'])))

    def test_documentation_runs_no_test_job(self):
        self.assertEqual({'java': '[]', 'frontend': 'false', 'detectors': 'false'},
                         run('README.md', 'docs/ci-cd.md'))

    def test_tools_change_runs_only_the_always_on_python_job(self):
        self.assertEqual({'java': '[]', 'frontend': 'false', 'detectors': 'false'},
                         run('tools/batch/batch_analyze.py', 'tools/batch/tests/test_batch_analyze.py'))
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        python = workflow[workflow.index('\n  python:'):workflow.index('\n  detector-contract:')]
        self.assertNotIn('needs:', python)
        self.assertNotIn('\n    if:', python)
        self.assertIn('detector-runtime, tools]', python)
        self.assertIn('python -m pytest -q batch/tests', python)

    def test_unknown_or_shared_paths_run_everything(self):
        for path in ('docker-compose.yml', '.github/workflows/ci.yml', 'infra/rabbitmq/rabbitmq.conf',
                     'sonar-project.properties', 'scripts/check-detector-image.sh', 'new-dir/x.py'):
            with self.subTest(path=path):
                self.assertEqual(EVERYTHING, scope([path]))

    def test_one_unknown_path_widens_a_narrow_change(self):
        self.assertEqual(EVERYTHING, scope(['frontend/src/App.tsx', '.env.example']))

    def test_required_checks_cannot_be_skipped_by_the_selection(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        java = workflow[workflow.index('\n  java:'):workflow.index('\n  python:')]
        # Branch protection requires each java (<service>) check: the job always runs and only its
        # test steps are gated, so unchanged services still compile classes for Sonar.
        self.assertEqual(['${{ !cancelled() }}'], re.findall(r'\n    if: (.+)', java))
        self.assertIn('-DskipTests test-compile', java)
        self.assertIn("needs.changes.outputs.java || '[\"gateway\",\"eureka-server\",\"orchestrator\","
                      "\"file-service\"]'", java)
        sonar = workflow[workflow.index('\n  sonar:'):]
        self.assertRegex(sonar, r"\n    if: \$\{\{ !cancelled\(\) &&")
        # A failed or unreadable selection widens to every job instead of skipping required ones.
        changes = workflow[workflow.index('\n  changes:'):workflow.index('\n  java:')]
        self.assertIn('python3 infra/ci/ci_scope.py', changes)
        self.assertIn('continue-on-error: true', changes)
        for job in ('frontend', 'detector-contract', 'ml-compatibility', 'detector-images',
                    'worker-redelivery'):
            with self.subTest(job=job):
                block = workflow[workflow.index(f'\n  {job}:'):]
                self.assertRegex(block[:400], r"if: \$\{\{ !cancelled\(\) && needs\.changes\.outputs\."
                                              r"\w+ != 'false' \}\}")


if __name__ == '__main__':
    unittest.main(verbosity=2)
