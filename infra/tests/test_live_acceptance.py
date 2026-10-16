"""Exercise fault-harness failures without Docker, media or detector models."""

import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('live_acceptance', ROOT / 'tests/live/live_acceptance.py')
live = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(live)


class LiveAcceptanceHarnessTest(unittest.TestCase):
    def setUp(self):
        self.case = live.LiveAcceptance('test_faults_keep_the_baseline_outcome')
        self.case.long_speech = Path('speech.flac')
        self.case.long_video = Path('video.mp4')
        self.case.api = Mock()
        self.case.analyse = Mock(return_value='analysis-id')
        self.case.wait_healthy = Mock()
        self.addCleanup(live.RESULTS.clear)

    def check_health(self, rows, expected_healthy):
        case = live.LiveAcceptance()
        with patch.object(live, 'run', return_value='\n'.join(json.dumps(row) for row in rows)) as run, \
                patch.object(live.time, 'monotonic', side_effect=[0, 0, 301]), \
                patch.object(live.time, 'sleep'):
            if expected_healthy:
                case.wait_healthy()
            else:
                with self.assertRaisesRegex(AssertionError, 'stack did not return to healthy'):
                    case.wait_healthy()
            self.assertIn('--all', run.call_args.args)

    def test_unhealthy_or_empty_stack_is_never_ready(self):
        for rows in ([], [{'State': 'restarting', 'Health': ''}],
                     [{'State': 'exited', 'ExitCode': 1, 'Health': ''}],
                     [{'Service': 'audio-detector', 'State': 'exited', 'ExitCode': 0, 'Health': ''}],
                     [{'State': 'running', 'Health': 'starting'}],
                     [{'State': 'running', 'Health': 'healthy'},
                      {'State': 'exited', 'ExitCode': 1, 'Health': ''}]):
            with self.subTest(rows=rows):
                self.check_health(rows, False)

    def test_completed_init_and_running_services_are_ready(self):
        self.check_health([{'State': 'running', 'Health': 'healthy'},
                           {'State': 'running', 'Health': ''},
                           {'Service': 'rabbitmq-init', 'State': 'exited', 'ExitCode': 0, 'Health': ''}], True)

    def fault(self, *, before='PROCESSING', after='COMPLETED', command_error=None):
        self.case.api.wait.return_value = {'status': 'COMPLETED', 'verdict': 'REAL'}
        self.case.api.get.side_effect = [{'status': before, 'verdict': 'REAL'},
                                        {'status': after, 'verdict': 'REAL'}] * 7
        with patch.object(live, 'run', side_effect=command_error) as run, \
                patch.object(live.time, 'sleep'):
            self.command = run
            self.case.test_faults_keep_the_baseline_outcome()
        return run

    def test_terminal_outcome_during_restart_is_not_misreported_as_too_late(self):
        run = self.fault()
        self.assertEqual(7, run.call_count)
        self.assertEqual(7, self.case.wait_healthy.call_count)
        for row in live.RESULTS:
            if row['scenario'].startswith('fault:'):
                self.assertIsNone(row['note'])

    def test_failure_after_restart_still_waits_for_recovery(self):
        self.case.api.wait.side_effect = [
            {'status': 'COMPLETED', 'verdict': 'REAL'},
            {'status': 'COMPLETED', 'verdict': 'REAL'},
            {'status': 'PROCESSING'},
            AssertionError('outcome unavailable'),
        ]
        with self.assertRaisesRegex(AssertionError, 'outcome unavailable'):
            self.fault(after='PROCESSING')
        self.case.wait_healthy.assert_called_once()

    def test_failed_restart_command_still_waits_for_recovery(self):
        with self.assertRaisesRegex(RuntimeError, 'restart failed'):
            self.fault(command_error=RuntimeError('restart failed'))
        self.case.wait_healthy.assert_called_once()

    def test_finished_analysis_is_detected_before_injecting_fault(self):
        with self.assertRaisesRegex(AssertionError, 'before the fault'):
            self.fault(before='COMPLETED')
        self.command.assert_not_called()
        self.case.wait_healthy.assert_called_once()

    def test_failed_recovery_stops_the_next_fault(self):
        self.case.api.wait.return_value = {'status': 'COMPLETED', 'verdict': 'REAL'}
        self.case.api.get.return_value = {'status': 'PROCESSING'}
        self.case.wait_healthy.side_effect = AssertionError('stack did not return to healthy')
        result = unittest.TestResult()
        with patch.object(live, 'run') as run, patch.object(live.time, 'sleep'):
            self.case.run(result)
        self.assertEqual(1, run.call_count)
        self.assertEqual(1, len(result.failures))


if __name__ == '__main__':
    unittest.main(verbosity=2)
