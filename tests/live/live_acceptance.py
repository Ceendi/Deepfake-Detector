"""Live acceptance: real media corpus and dependency faults against a running full stack.

Needs the core, auth and ml profiles with the dev realm (KEYCLOAK_REALM_CONFIG=dev/realm-export.json),
detector models, ffmpeg and Docker Compose. Detector models are not in the repository, so this suite
runs locally, not in CI. See tests/live/README.md.

    LIVE_COMPOSE="docker compose -p dfd-e2e --env-file e2e.env --profile core --profile auth --profile ml" \
        python tests/live/live_acceptance.py
"""

import json
import os
import shlex
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
GATEWAY = os.environ.get('LIVE_GATEWAY', 'http://localhost:8080')
KEYCLOAK = os.environ.get('LIVE_KEYCLOAK', 'http://localhost:8180')
CLIENT = os.environ.get('LIVE_CLIENT_ID', 'deepfake-loadtest')
SECRET = os.environ.get('LIVE_CLIENT_SECRET', 'changeme_dev_loadtest')
COMPOSE = shlex.split(os.environ.get('LIVE_COMPOSE', 'docker compose'))
SAMPLE = Path(os.environ.get('LIVE_SAMPLE', ROOT / 'load-tests/jmeter/sample-5mb.mp4'))
ANALYSIS_TIMEOUT = float(os.environ.get('LIVE_ANALYSIS_TIMEOUT', '900'))
REPORT = os.environ.get('LIVE_REPORT')
TERMINAL = {'COMPLETED', 'FAILED', 'CANCELLED'}
RESULTS = []


def run(*command, timeout=600):
    return subprocess.run(command, check=True, capture_output=True, text=True, timeout=timeout).stdout


def ffmpeg(*args, output):
    run('ffmpeg', '-v', 'error', '-y', *args, str(output))
    return output


class Api:
    def __init__(self):
        self.token = None
        self.expires = 0.0

    def bearer(self):
        if time.monotonic() > self.expires:
            body = urlencode({'grant_type': 'client_credentials', 'client_id': CLIENT,
                              'client_secret': SECRET}).encode()
            with urlopen(Request(f'{KEYCLOAK}/realms/deepfake/protocol/openid-connect/token',
                                 data=body), timeout=30) as response:
                data = json.load(response)
            self.token = data['access_token']
            self.expires = time.monotonic() + data['expires_in'] - 30
        return self.token

    def call(self, method, path, body=None, headers=None, timeout=120):
        headers = {'Authorization': 'Bearer ' + self.bearer(), **(headers or {})}
        try:
            with urlopen(Request(GATEWAY + path, data=body, method=method, headers=headers),
                         timeout=timeout) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw) if raw else None
            except ValueError:
                return error.code, raw.decode(errors='replace')

    def upload(self, path, mime):
        boundary = uuid.uuid4().hex
        head = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
                f'Content-Type: {mime}\r\n\r\n').encode()
        body = head + path.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
        return self.call('POST', '/api/files/upload', body,
                         {'Content-Type': f'multipart/form-data; boundary={boundary}'}, timeout=600)

    def create(self, file_id, kind):
        status, body = self.call('POST', '/api/analysis', json.dumps({'fileId': file_id, 'type': kind}).encode(),
                                 {'Content-Type': 'application/json'})
        if status != 201:
            raise AssertionError(f'POST /api/analysis -> {status} {body}')
        return body['id']

    def get(self, analysis_id):
        # Tolerates the gateway or orchestrator restarting under a fault.
        for _ in range(60):
            try:
                status, body = self.call('GET', f'/api/analysis/{analysis_id}', timeout=30)
                if status == 200:
                    return body
            except OSError:
                pass
            time.sleep(2)
        raise AssertionError(f'analysis {analysis_id} unreadable')

    def wait(self, analysis_id, until=TERMINAL, timeout=ANALYSIS_TIMEOUT):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            analysis = self.get(analysis_id)
            if analysis['status'] in until:
                return analysis
            time.sleep(2)
        raise AssertionError(f'analysis {analysis_id} not in {until} after {timeout}s: {analysis}')


def outcome(analysis):
    return {'status': analysis['status'], 'verdict': analysis.get('verdict'),
            'error': (analysis.get('errorMessage') or '')[:80] or None}


def record(name, expected, actual, seconds=None, note=None):
    RESULTS.append({'scenario': name, 'expected': expected, 'actual': actual,
                    'seconds': None if seconds is None else round(seconds, 1), 'note': note})


def dead_letters():
    out = run(*COMPOSE, 'exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages')
    counts = dict(line.split()[:2] for line in out.splitlines() if line.strip() and not line.startswith('name'))
    return {name: int(count) for name, count in counts.items()
            if name.endswith(('.dlq', 'quarantine'))}


class LiveAcceptance(unittest.TestCase):
    api = Api()

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='live-acceptance-'))
        cls.audio = ffmpeg('-i', str(SAMPLE), '-vn', '-ac', '1', '-ar', '16000', output=cls.tmp / 'speech.wav')
        cls.cover = ffmpeg('-f', 'lavfi', '-i', 'color=c=black:s=3000x3000', '-frames:v', '1',
                           output=cls.tmp / 'cover.jpg')
        # Long inputs keep the detectors busy long enough for a fault to land mid-analysis.
        cls.long_speech = ffmpeg('-stream_loop', '3', '-i', str(cls.audio), '-c:a', 'flac',
                                 output=cls.tmp / 'long-speech.flac')
        cls.long_video = ffmpeg('-stream_loop', '3', '-i', str(SAMPLE), '-an', '-c:v', 'copy',
                                output=cls.tmp / 'long-video.mp4')
        cls.dead_letters_before = dead_letters()

    # --- Corpus: upload validation and detector outcomes on real media ---

    def test_corpus_rejections_happen_at_upload(self):
        cases = {
            'video above 4K': (ffmpeg('-f', 'lavfi', '-i', 'color=c=black:s=4096x2160:r=1', '-frames:v', '1',
                                      '-c:v', 'mpeg4', output=self.tmp / 'above4k.mp4'), 'video/mp4',
                               'MEDIA_RESOLUTION_TOO_HIGH'),
            'audio over 30 min': (ffmpeg('-f', 'lavfi', '-i', 'anullsrc=r=8000:cl=mono', '-t', '1860',
                                         '-c:a', 'libmp3lame', '-b:a', '8k', output=self.tmp / 'long.mp3'),
                                  'audio/mpeg', None),
            'random bytes as mp4': (self.write('noise.mp4', os.urandom(256 * 1024)), 'video/mp4', None),
            'webm container': (ffmpeg('-f', 'lavfi', '-i', 'color=c=gray:s=320x240:r=10', '-t', '2',
                                      '-c:v', 'libvpx-vp9', output=self.tmp / 'clip.webm'), 'video/webm', None),
        }
        for name, (path, mime, code) in cases.items():
            with self.subTest(name):
                status, body = self.api.upload(path, mime)
                record(f'upload: {name}', '422', str(status), note=(body or {}).get('message') if isinstance(body, dict) else None)
                self.assertEqual(422, status, body)
                if code:
                    self.assertIn(code, body['message'])

    def test_corpus_accepted_media_reach_a_domain_outcome(self):
        cases = {
            'mp3 with 3000x3000 cover': (ffmpeg('-i', str(self.audio), '-i', str(self.cover), '-map', '0:a',
                                                '-map', '1:v', '-c:v', 'mjpeg', '-disposition:v', 'attached_pic',
                                                output=self.tmp / 'cover.mp3'), 'audio/mpeg', 'AUDIO'),
            'flac speech': (ffmpeg('-i', str(self.audio), output=self.tmp / 'speech.flac'), 'audio/flac', 'AUDIO'),
            'wav named .mp4': (self.copy(self.audio, 'audio-named.mp4'), 'video/mp4', 'AUDIO'),
            'avi mjpeg': (ffmpeg('-i', str(SAMPLE), '-t', '8', '-c:v', 'mjpeg', '-q:v', '5', '-c:a', 'pcm_s16le',
                                 output=self.tmp / 'clip.avi'), 'video/x-msvideo', 'FULL'),
            'portrait 1080x1920': (ffmpeg('-i', str(SAMPLE), '-t', '8', '-vf', 'scale=1080:1920', '-c:a', 'copy',
                                          output=self.tmp / 'portrait.mp4'), 'video/mp4', 'VIDEO'),
            'video without audio': (ffmpeg('-i', str(SAMPLE), '-t', '8', '-an', '-c:v', 'copy',
                                           output=self.tmp / 'silent.mp4'), 'video/mp4', 'FULL'),
            'truncated mp4': (self.write('truncated.mp4', SAMPLE.read_bytes()[: SAMPLE.stat().st_size // 3]),
                              'video/mp4', 'FULL'),
        }
        for name, (path, mime, kind) in cases.items():
            with self.subTest(name):
                status, body = self.api.upload(path, mime)
                if status not in (200, 201):
                    record(f'analysis: {name}', 'domain outcome', f'upload {status}',
                           note=(body or {}).get('message') if isinstance(body, dict) else None)
                    self.assertEqual(422, status, body)  # rejecting broken media at upload is fine
                    continue
                started = time.monotonic()
                analysis = self.api.wait(self.api.create(body['fileId'], kind))
                record(f'analysis: {name}', 'COMPLETED or domain FAILED', outcome(analysis),
                       time.monotonic() - started)
                self.assertIn(analysis['status'], {'COMPLETED', 'FAILED'})
                # A failure must carry a domain code, never the infrastructure timeout or DLQ marker.
                self.assertNotIn('[ANALYSIS_TIMEOUT]', analysis.get('errorMessage') or '')
                self.assertNotIn('[DEAD_LETTERED]', analysis.get('errorMessage') or '')

    # --- Faults: the same input must end the same way as the undisturbed baseline ---

    def analyse(self, path, mime, kind):
        status, body = self.api.upload(path, mime)
        self.assertIn(status, (200, 201), body)
        return self.api.create(body['fileId'], kind)

    def test_faults_keep_the_baseline_outcome(self):
        inputs = {'AUDIO': (self.long_speech, 'audio/flac'), 'VIDEO': (self.long_video, 'video/mp4')}
        baselines = {}
        for kind, (path, mime) in inputs.items():
            started = time.monotonic()
            baselines[kind] = outcome(self.api.wait(self.analyse(path, mime, kind)))
            record(f'baseline {kind} ({path.name})', 'terminal', baselines[kind], time.monotonic() - started)
        # (input kind, compose command, seconds after PROCESSING or None to inject while downloading)
        faults = {
            'postgres restart': ('AUDIO', ['restart', 'postgres'], 5),
            'rabbitmq restart': ('AUDIO', ['restart', 'rabbitmq'], 5),
            'redis restart': ('AUDIO', ['restart', 'redis'], 5),
            'seaweedfs restart': ('AUDIO', ['restart', 'seaweedfs'], None),
            'orchestrator crash': ('AUDIO', ['restart', '-t', '0', 'orchestrator'], 5),
            'audio-detector crash': ('AUDIO', ['restart', '-t', '0', 'audio-detector'], 5),
            # A face-less video is rejected within seconds, so crash it as soon as work starts.
            'video-detector crash': ('VIDEO', ['restart', '-t', '0', 'video-detector'], 0),
        }
        for name, (kind, command, delay) in faults.items():
            with self.subTest(name):
                path, mime = inputs[kind]
                analysis_id = self.analyse(path, mime, kind)
                if delay is not None:
                    self.api.wait(analysis_id, until={'PROCESSING'} | TERMINAL, timeout=300)
                    time.sleep(delay)
                run(*COMPOSE, *command)
                in_flight = self.api.get(analysis_id)['status'] not in TERMINAL
                started = time.monotonic()
                actual = outcome(self.api.wait(analysis_id))
                record(f'fault: {name} ({kind})', baselines[kind], actual, time.monotonic() - started,
                       note=None if in_flight else 'analysis finished before the fault landed')
                self.assertTrue(in_flight, 'fault landed after the analysis had finished')
                self.assertEqual(baselines[kind]['status'], actual['status'], actual)
                self.assertEqual(baselines[kind]['verdict'], actual['verdict'], actual)
                self.wait_healthy()

    def test_zz_no_new_dead_letters(self):
        after = dead_letters()
        grown = {name: after.get(name, 0) - self.dead_letters_before.get(name, 0) for name in after}
        record('dead letters / quarantine growth', '0', grown)
        self.assertFalse({name: count for name, count in grown.items() if count > 0}, grown)

    # --- helpers ---

    def write(self, name, data):
        path = self.tmp / name
        path.write_bytes(data)
        return path

    def copy(self, source, name):
        return self.write(name, Path(source).read_bytes())

    def wait_healthy(self, timeout=300):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            rows = [json.loads(line) for line in run(*COMPOSE, 'ps', '--format', 'json').splitlines() if line.strip()]
            if all(row.get('Health') in ('', 'healthy') for row in rows if row.get('State') == 'running'):
                return
            time.sleep(5)
        self.fail('stack did not return to healthy')


def write_report():
    lines = ['| Scenario | Expected | Actual | Seconds | Note |', '|---|---|---|---|---|']
    for row in RESULTS:
        lines.append('| {scenario} | {expected} | {actual} | {seconds} | {note} |'.format(
            **{key: json.dumps(value, ensure_ascii=False) if isinstance(value, dict) else ('' if value is None else value)
               for key, value in row.items()}))
    text = '\n'.join(lines)
    print(text)
    if REPORT:
        Path(REPORT).write_text(text + '\n', encoding='utf-8')


if __name__ == '__main__':
    try:
        unittest.main(verbosity=2, exit=False)
    finally:
        write_report()
