"""Guard the real-model E2E workflow and its outcome check (infra/ci/e2e_ml_outcomes.py).

Run with Python 3.9+: python3 infra/tests/test_e2e_ml.py
"""

import importlib.util
import re
import tempfile
import unittest
from pathlib import Path

from test_discovery import ROOT

SPEC = importlib.util.spec_from_file_location('e2e_ml_outcomes', ROOT / 'infra/ci/e2e_ml_outcomes.py')
outcomes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(outcomes)

WORKFLOW = ROOT / '.github/workflows/e2e-ml.yml'
HEADER = 'plik,typ,status,werdykt,prob_wideo,prob_audio,blad,czas_s'


def row(name, status='COMPLETED', verdict='REAL', video='', audio='0.12', error=''):
    return {'plik': name, 'typ': '', 'status': status, 'werdykt': verdict, 'prob_wideo': video,
            'prob_audio': audio, 'blad': error, 'czas_s': '1.0'}


GOOD = [
    row('fake/speech-kal.wav', verdict='FAKE', audio='0.9'),
    row('fake/speech-slt.mp3'),
    row('other/no-face.mp4', status='FAILED', verdict='', audio='',
        error='[NO_FACE_DETECTED] No face found'),
    row('real/speaker-30s.mp4', video='0.04', audio='0.02'),
]
FILES = [item['plik'] for item in GOOD]


class OutcomeCheckTest(unittest.TestCase):
    def test_expected_outcomes_pass_whatever_the_verdict(self):
        self.assertEqual([], outcomes.problems(GOOD, FILES))

    def test_every_wrong_outcome_is_reported(self):
        cases = {
            'audio without verdict': row('fake/speech-kal.wav', status='FAILED', verdict=''),
            'audio probability out of range': row('fake/speech-kal.wav', audio='1.5'),
            'audio with a video probability': row('fake/speech-kal.wav', video='0.1'),
            'face-less video completed': row('other/no-face.mp4', video='0.3'),
            'face-less video with another code': row('other/no-face.mp4', status='FAILED',
                                                     verdict='', error='[MODEL_UNAVAILABLE] x'),
            'face video failed': row('real/speaker-30s.mp4', status='FAILED', verdict=''),
            'face video without video probability': row('real/speaker-30s.mp4'),
            'client gave up': row('fake/speech-slt.mp3', status='ERROR', verdict='',
                                  error='[CLIENT_TIMEOUT] x'),
        }
        for case, broken in cases.items():
            with self.subTest(case=case):
                rows = [broken if item['plik'] == broken['plik'] else item for item in GOOD]
                self.assertEqual(1, len(outcomes.problems(rows, FILES)))

    def test_missing_and_duplicate_rows_are_reported(self):
        self.assertTrue(outcomes.problems(GOOD[1:], FILES))
        self.assertTrue(outcomes.problems(GOOD + GOOD[:1], FILES))

    def test_command_line_reads_the_csv_and_the_media_folder(self):
        with tempfile.TemporaryDirectory() as directory:
            media = Path(directory) / 'media'
            for name in FILES + ['notes/readme.txt']:
                (media / name).parent.mkdir(parents=True, exist_ok=True)
                (media / name).write_bytes(b'')
            results = Path(directory) / 'results.csv'
            lines = [HEADER] + [','.join(item[column] for column in HEADER.split(','))
                                for item in GOOD]
            results.write_text('\n'.join(lines) + '\n', encoding='utf-8')
            self.assertEqual(0, outcomes.main([str(results), str(media)]))
            results.write_text(HEADER + '\n', encoding='utf-8')
            self.assertEqual(1, outcomes.main([str(results), str(media)]))


class RealModelWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text()

    def test_runs_only_on_schedule_and_on_demand(self):
        triggers = self.workflow.split('\non:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertEqual({'schedule', 'workflow_dispatch'},
                         set(re.findall(r'(?m)^  ([a-z_]+):', triggers)))
        self.assertIn("- cron: '0 3 1,15 * *'", triggers)
        self.assertIn('default: latest', triggers)

    def test_published_images_run_without_building(self):
        self.assertNotIn('--build', self.workflow)
        self.assertEqual(2, self.workflow.count('up -d --no-build --pull never'))
        self.assertIn('$COMPOSE pull', self.workflow)
        self.assertIn("DEEPFAKE_IMAGE_TAG: ${{ inputs.image_tag || 'latest' }}", self.workflow)
        self.assertNotIn('--profile monitoring', self.workflow)
        self.assertIn("grep -qx 'FILE_SERVICE_REPLICAS=1' .env", self.workflow)

    def test_downloaded_clip_is_pinned_and_media_is_generated(self):
        self.assertRegex(self.workflow, r'FACE_SHA256: [0-9a-f]{64}\n')
        self.assertIn('sha256sum -c -', self.workflow)
        self.assertIn('--labels-from-folders --timeout 900', self.workflow)
        self.assertIn('python3 infra/ci/e2e_ml_outcomes.py results.csv media', self.workflow)
        self.assertIn("grep -F 'do analizy: 0' resume.log", self.workflow)

    def test_batch_cli_in_workflows_never_waits_for_a_browser(self):
        # Without a client secret the CLI signs in through the device flow and polls until the
        # code expires; CI must use the dev realm's service account instead.
        calls = []
        for workflow in sorted((ROOT / '.github/workflows').glob('*.y*ml')):
            for line in workflow.read_text().splitlines():
                if 'batch_analyze.py' in line and not line.lstrip().startswith('#'):
                    calls.append((workflow.name, line))
        self.assertTrue(calls)
        for name, line in calls:
            with self.subTest(workflow=name, line=line.strip()):
                self.assertIn('--client-secret "$LOADTEST_CLIENT_SECRET"', line)
        self.assertIn("grep -m1 '^LOADTEST_CLIENT_SECRET=' .env", self.workflow)
        self.assertIn('>> "$GITHUB_ENV"', self.workflow)

    def test_actions_are_pinned(self):
        actions = re.findall(r'(?m)^\s+(?:- )?uses: (\S+)', self.workflow)
        self.assertTrue(actions)
        for action in actions:
            self.assertRegex(action, r'@[0-9a-f]{40}$')


if __name__ == '__main__':
    unittest.main(verbosity=2)
