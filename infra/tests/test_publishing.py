"""Exercise publishing decisions without credentials, Docker or network calls."""

import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('publishing', ROOT / 'infra/ci/publishing.py')
publishing = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publishing)
SHA = 'a' * 40
GREEN = {'headSha': SHA, 'conclusion': 'success', 'event': 'push', 'headBranch': 'main'}


class PublishingTest(unittest.TestCase):
    def select(self, event, *, payload=None, runs=None, existing=None, attempt='1'):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / 'event.json', Path(directory) / 'output'
            source.write_text(json.dumps(payload or {}))
            env = {'GITHUB_REPOSITORY': 'Owner/Repo', 'GITHUB_EVENT_NAME': event,
                   'GITHUB_EVENT_PATH': str(source), 'GITHUB_OUTPUT': str(output),
                   'GITHUB_RUN_NUMBER': '42', 'GITHUB_RUN_ATTEMPT': attempt, 'NO_CACHE': 'false'}
            with patch.dict(os.environ, env), patch.object(publishing, 'command',
                    return_value=json.dumps(runs or [])) as command, patch.object(
                        publishing, 'manifest', return_value=existing):
                publishing.select()
                return dict(line.split('=', 1) for line in output.read_text().splitlines()), command

    def test_scheduled_and_manual_runs_resolve_green_ci_instead_of_head(self):
        for event in ('schedule', 'workflow_dispatch'):
            output, command = self.select(event, runs=[GREEN])
            self.assertEqual(SHA, output['sha'])
            self.assertIn('ci.yml', command.call_args.args)
            self.assertIn('push', command.call_args.args)
            self.assertIn('success', command.call_args.args)
        self.assertEqual('sha-aaaaaaa-r42-a1', self.select('schedule', runs=[GREEN])[0]['tag'])

    def test_no_green_ci_fails_closed(self):
        with self.assertRaises(ValueError):
            self.select('schedule')

    def test_original_tag_is_never_reused_for_a_rebuild_or_rerun(self):
        self.assertEqual('sha-aaaaaaa', self.select('workflow_dispatch', runs=[GREEN])[0]['tag'])
        for kwargs in ({'existing': {'config': {}}}, {'attempt': '2'}):
            output, _ = self.select('workflow_dispatch', runs=[GREEN], **kwargs)
            self.assertRegex(output['tag'], r'^sha-aaaaaaa-r42-a[12]$')

    def test_ci_from_another_repository_cannot_publish(self):
        with self.assertRaises(ValueError):
            self.select('workflow_run', payload={'workflow_run': {
                'head_repository': {'full_name': 'attacker/fork'}}})

    def test_old_ci_cannot_move_latest(self):
        env = {'GITHUB_REPOSITORY': 'Owner/Repo', 'SHA': SHA, 'TAG': 'sha-aaaaaaa'}
        with patch.dict(os.environ, env), patch.object(publishing, 'command',
                                                       return_value='b' * 40) as command:
            publishing.promote()
        self.assertEqual(1, command.call_count)
        self.assertEqual('gh', command.call_args.args[0])

    def test_latest_moves_only_for_current_main_and_uses_existing_images(self):
        env = {'GITHUB_REPOSITORY': 'Owner/Repo', 'SHA': SHA, 'TAG': 'sha-aaaaaaa-r42-a1'}
        with patch.dict(os.environ, env), patch.object(publishing, 'command',
                                                       return_value=SHA) as command:
            publishing.promote()
        pushes = [call.args for call in command.call_args_list if call.args[0] == 'docker']
        self.assertEqual(len(publishing.SERVICES), len(pushes))
        for call in pushes:
            self.assertIn('--prefer-index=false', call)
            self.assertTrue(call[-1].endswith(':sha-aaaaaaa-r42-a1'))

    def test_a_partial_rerun_cannot_overwrite_a_tag_with_new_content(self):
        env = {'GITHUB_REPOSITORY': 'Owner/Repo', 'SERVICE': 'gateway', 'TAG': 'sha-aaaaaaa'}
        with patch.dict(os.environ, env), patch.object(publishing, 'manifest',
                return_value={'config': {'digest': 'sha256:old'}}), patch.object(
                    publishing, 'command', return_value='sha256:new') as command, \
                self.assertRaisesRegex(ValueError, 'immutable'):
            publishing.push_image()
        self.assertEqual(1, command.call_count)

    def test_registry_errors_are_not_interpreted_as_a_missing_tag(self):
        import subprocess

        for message in ('unauthorized', 'connection timed out', 'too many requests', 'host not found'):
            result = subprocess.CompletedProcess([], 1, '', message)
            with patch.object(publishing.subprocess, 'run', return_value=result), \
                    self.assertRaises(RuntimeError):
                publishing.manifest('example:image')


if __name__ == '__main__':
    unittest.main(verbosity=2)
