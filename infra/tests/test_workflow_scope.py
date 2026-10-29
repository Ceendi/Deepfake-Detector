"""Keep path selection inside workflows so every PR emits the required status checks."""

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('workflow_scope', ROOT / 'infra/ci/workflow_scope.py')
scope = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scope)
PATTERNS = json.loads((ROOT / 'infra/ci/workflow_paths.json').read_text())


class WorkflowScopeTest(unittest.TestCase):
    def test_empty_or_unreadable_selection_runs_every_workflow(self):
        for patterns in PATTERNS.values():
            self.assertTrue(scope.selected([], patterns))

    def test_docs_skip_expensive_jobs_but_ci_policy_changes_do_not(self):
        for patterns in PATTERNS.values():
            self.assertFalse(scope.selected(['docs/ci-cd.md'], patterns))
            self.assertTrue(scope.selected(['infra/ci/publishing.py'], patterns))

    def test_old_name_selects_stack_tests_when_a_file_moves_to_docs(self):
        self.assertTrue(scope.selected(['docs/config.yaml', 'infra/prometheus/prometheus.yml'],
                                       PATTERNS['infra-smoke']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
