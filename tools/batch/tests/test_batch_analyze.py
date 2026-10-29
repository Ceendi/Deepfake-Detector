"""Batch CLI without the stack: metrics, labels, resume, retries and a fake REST API over HTTP.

    python -m unittest discover -s tools/batch/tests -v
"""

import csv
import io
import json
import re
import sys
import tempfile
import threading
import unittest
import uuid
from contextlib import redirect_stdout
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import URLError
from urllib.parse import parse_qs

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import batch_analyze as ba


def row(plik, status='COMPLETED', werdykt='', blad=''):
    return {**dict.fromkeys(ba.COLUMNS, ''), 'plik': plik, 'status': status, 'werdykt': werdykt,
            'blad': blad, 'czas_s': '1.0'}


def touch(root, rel, size=16):
    path = Path(root, rel)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b'\0' * size)
    return path


class MetricsTest(unittest.TestCase):
    def test_known_confusion_matrix(self):
        rows = ([row(f'fake/tp{i}.mp4', werdykt='FAKE') for i in range(8)]
                + [row(f'fake/fn{i}.mp4', werdykt='REAL') for i in range(4)]
                + [row(f'real/fp{i}.mp4', werdykt='FAKE') for i in range(2)]
                + [row(f'real/tn{i}.mp4', werdykt='REAL') for i in range(5)])
        m = ba.compute_metrics(rows)
        self.assertEqual({'TP': 8, 'FP': 2, 'TN': 5, 'FN': 4}, m['confusion_matrix'])
        self.assertAlmostEqual(0.8, m['precision'])
        self.assertAlmostEqual(8 / 12, m['recall'])
        self.assertAlmostEqual(2 * 0.8 * (8 / 12) / (0.8 + 8 / 12), m['f1'])
        self.assertAlmostEqual(13 / 19, m['accuracy'])
        self.assertAlmostEqual(5 / 7, m['specificity'])
        self.assertAlmostEqual((8 / 12 + 5 / 7) / 2, m['balanced_accuracy'])
        self.assertEqual(19, m['predicted'])

    def test_zero_divisions_are_undefined_not_errors(self):
        only_real = ba.compute_metrics([row('real/a.wav', werdykt='REAL'), row('real/b.wav', werdykt='REAL')])
        self.assertEqual((None, None, None, None), (only_real['precision'], only_real['recall'],
                                                    only_real['f1'], only_real['balanced_accuracy']))
        self.assertEqual(1.0, only_real['accuracy'])
        missed_fakes = ba.compute_metrics([row('fake/a.mp4', werdykt='REAL')])
        self.assertIsNone(missed_fakes['precision'])
        self.assertEqual((0.0, 0.0, 0.0), (missed_fakes['recall'], missed_fakes['f1'], missed_fakes['accuracy']))
        empty = ba.compute_metrics([])
        self.assertEqual((0, None), (empty['predicted'], empty['accuracy']))
        self.assertIn('n/d', ba.format_metrics(empty))

    def test_errors_and_unlabeled_files_are_not_predictions(self):
        m = ba.compute_metrics([
            row('fake/a.mp4', werdykt='FAKE'),
            row('fake/b.mp4', 'FAILED', blad='[NO_FACE_DETECTED] Nie wykryto twarzy.'),
            row('real/c.mp4', 'FAILED', blad='[NO_FACE_DETECTED] Nie wykryto twarzy.'),
            row('real/d.wav', 'REJECTED', blad='[UNSUPPORTED_MEDIA] Unsupported.'),
            row('real/e.mp4', 'CANCELLED'),
            row('misc/f.mp4', werdykt='REAL'),
        ])
        self.assertEqual(1, m['predicted'])
        self.assertEqual({'CANCELLED': 1, 'NO_FACE_DETECTED': 2, 'UNSUPPORTED_MEDIA': 1}, m['excluded_by_code'])
        self.assertEqual({'NO_FACE_DETECTED': 1}, m['excluded_by_label']['fake'])
        self.assertEqual((1, ['misc/f.mp4']), (m['unlabeled'], m['unlabeled_files']))
        self.assertEqual((6, 5), (m['files'], m['labelled']))

    def test_last_row_of_a_file_wins(self):
        m = ba.compute_metrics([row('fake/a.mp4', 'ERROR', blad='[CLIENT_TIMEOUT] x'),
                                row('fake/a.mp4', werdykt='FAKE')])
        self.assertEqual((1, {}), (m['confusion_matrix']['TP'], m['excluded_by_code']))


class LabelAndDiscoveryTest(unittest.TestCase):
    def test_label_is_the_nearest_real_or_fake_folder(self):
        cases = {'fake/x.mp4': 'fake', 'Real/sub/x.mp4': 'real', 'data/FAKE/real/x.mp4': 'real',
                 'real/fake/x.mp4': 'fake', 'x.mp4': None, 'fake.mp4': None, 'misc/fakes/x.mp4': None,
                 'a\\Fake\\b.wav': 'fake'}
        for rel, label in cases.items():
            with self.subTest(rel=rel):
                self.assertEqual(label, ba.label_for(rel))

    def test_discovery_filters_extensions_case_insensitively_in_stable_order(self):
        with tempfile.TemporaryDirectory() as root:
            for rel in ('b/clip.MP4', 'a/z.flac', 'a/y.Mov', 'c.avi', 'd.WAV', 'e.mp3', 'notes.txt',
                        'clip.webm', '._clip.mp4', '.git/x.mp4', 'a/sub/x.mkv'):
                touch(root, rel)
            found = ba.discover(root)
        self.assertEqual(['a/y.Mov', 'a/z.flac', 'b/clip.MP4', 'c.avi', 'd.WAV', 'e.mp3'], [f.rel for f in found])
        self.assertEqual(['FULL', 'AUDIO', 'FULL', 'FULL', 'AUDIO', 'AUDIO'], [f.guessed_type for f in found])


class ResumeTest(unittest.TestCase):
    rows = (row('a.mp4', werdykt='FAKE'), row('b.mp4', 'FAILED', blad='[NO_FACE_DETECTED] x'),
            row('c.mp4', 'REJECTED'), row('d.mp4', 'ERROR', blad='[CLIENT_TIMEOUT] x'), row('e.mp4', 'CANCELLED'),
            row('f.mp4', 'COMPL'), row('g.mp4', 'ERROR'), row('g.mp4', werdykt='REAL'))

    def test_transient_errors_and_cut_rows_are_retried_by_default(self):
        self.assertEqual(['a.mp4', 'b.mp4', 'c.mp4', 'e.mp4', 'g.mp4'], sorted(ba.plan_resume(self.rows)))

    def test_retry_failed_keeps_only_completed(self):
        self.assertEqual(['a.mp4', 'g.mp4'], sorted(ba.plan_resume(self.rows, retry_failed=True)))

    def test_rows_cut_after_a_known_status_are_retried(self):
        for status in ba.KNOWN_STATUSES:
            with self.subTest(status=status):
                self.assertTrue(ba.should_retry({**row('a.mp4', status), 'czas_s': None}, False))
                self.assertTrue(ba.should_retry({**row('a.mp4', status), 'czas_s': ''}, False))

    def test_atomic_rewrite_syncs_before_replace(self):
        with tempfile.TemporaryDirectory() as tmp:
            events = []
            replace = ba.os.replace
            with patch.object(ba.os, 'fsync', side_effect=lambda fd: events.append('sync')), \
                    patch.object(ba.os, 'replace', side_effect=lambda *args: (events.append('replace'), replace(*args))):
                ba.write_results(Path(tmp, 'r.csv'), self.rows)
            self.assertEqual(['sync', 'replace'], events)

    def test_resume_refuses_a_legacy_csv_without_root_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp, 'r.csv')
            ba.write_results(out, self.rows)
            before = out.read_bytes()
            with patch.object(ba, 'log'):
                self.assertEqual(2, ba.main([tmp, '--out', str(out)]))
            self.assertEqual(before, out.read_bytes())

    def test_rewrite_keeps_one_row_per_file_and_rejects_foreign_csv(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp, 'r.csv')
            ba.write_results(out, ba.plan_resume(self.rows).values())
            self.assertEqual(['a.mp4', 'b.mp4', 'c.mp4', 'e.mp4', 'g.mp4'], [r['plik'] for r in ba.read_results(out)])
            self.assertEqual('plik,typ,status,werdykt,prob_wideo,prob_audio,blad,czas_s',
                             out.read_text(encoding='utf-8').splitlines()[0])
            foreign = Path(tmp, 'other.csv')
            foreign.write_text('a,b\n1,2\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                ba.read_results(foreign)
            with patch.object(ba, 'log'):
                self.assertEqual(2, ba.main([tmp, '--out', str(foreign)]))


class UploadCleanupTest(unittest.TestCase):
    def test_timeout_upload_is_released_only_after_row_is_durable(self):
        api = Mock(upload_timeout=900)
        api.request.side_effect = [ba.Response(200, {}, {'fileId': 'file', 'mimetype': 'video/mp4'}),
                                   ba.Response(201, {}, {'id': 'analysis'}),
                                   ba.Response(200, {}, {'status': 'PROCESSING'})]
        api.cancel.return_value = ba.Response(200, {}, None)
        api.release.return_value = ba.Response(204, {}, None)
        with tempfile.TemporaryDirectory() as tmp, patch.object(ba, 'log'):
            item = ba.MediaFile(touch(tmp, 'a.mp4'), 'a.mp4', 16)
            batch = ba.Batch(api, ba.Settings(timeout=0.000001), wait=lambda s: False)
            written = []

            def write(result):
                api.release.assert_not_called()
                written.append(result)

            self.assertEqual(0, batch.run([item], write))
        self.assertEqual('ERROR', written[0]['status'])
        api.cancel.assert_called_once_with('analysis', ba.CANCEL_TIMEOUT)
        api.release.assert_called_once_with('file', ba.CANCEL_TIMEOUT)

    def test_retry_failed_releases_previous_attempt_before_replacing_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp, 'r.csv')
            media = Path(tmp, 'media')
            item = ba.MediaFile(touch(media, 'a.mp4'), 'a.mp4', 16)
            state = ba.RunState(out, media)
            state.put(item.rel, {'file_id': 'old-file', 'analysis_id': 'old-analysis'})
            state = ba.RunState(out, media)
            api = Mock(upload_timeout=900)
            api.cancel.return_value = ba.Response(409, {}, None)
            api.release.return_value = ba.Response(204, {}, None)
            api.request.side_effect = [ba.Response(200, {}, {'fileId': 'new-file'}),
                                       ba.Response(201, {}, {'id': 'new-analysis'}),
                                       ba.Response(200, {}, {'status': 'COMPLETED'})]
            batch = ba.Batch(api, ba.Settings(), wait=lambda s: False, state=state)
            with patch.object(ba, 'log'):
                batch.run([item], lambda result: None)
            self.assertEqual('old-file', api.release.call_args.args[0])
            self.assertEqual('new-file', state.get(item.rel)['file_id'])

    def test_interrupt_releases_only_cancelled_uploads(self):
        api = Mock()
        api.cancel.side_effect = [ba.Response(200, {}, None), OSError('offline')]
        api.release.return_value = ba.Response(204, {}, None)
        batch = ba.Batch(api, ba.Settings())
        batch.active.update({'a', 'b'})
        batch.state.put('a.mp4', {'file_id': 'fa', 'analysis_id': 'a'})
        batch.state.put('b.mp4', {'file_id': 'fb', 'analysis_id': 'b'})
        with patch.object(ba, 'log'):
            batch.cancel_active()
        self.assertEqual(1, api.release.call_count)
        self.assertEqual(1, len(batch.state.attempts))


    def test_unresolved_create_is_recovered_after_restart_without_upload_or_post(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(ba, 'log'):
            out = Path(tmp, 'r.csv')
            item = ba.MediaFile(touch(tmp, 'a.mp4'), 'a.mp4', 16)
            state = ba.RunState(out, tmp)
            state.put(item.rel, {'file_id': 'file', 'creating': True})
            api = Mock()
            api.request.side_effect = [ba.Response(200, {}, {'content': [{'id': 'analysis', 'fileId': 'file'}],
                                                           'page': {'totalPages': 1}}),
                                       ba.Response(200, {}, {'status': 'COMPLETED', 'verdict': 'REAL'})]
            batch = ba.Batch(api, ba.Settings(), state=ba.RunState(out, tmp))
            rows = []
            batch.run([item], rows.append)
            self.assertEqual('COMPLETED', rows[0]['status'])
            self.assertTrue(all(call.args[0] == 'GET' for call in api.request.call_args_list))
            api.release.assert_not_called()
            self.assertEqual('analysis', ba.RunState(out, tmp).get(item.rel)['analysis_id'])

    def test_cleanup_failure_preserves_attempt_and_prevents_another_upload(self):
        for cancel_status, release_status in ((503, 204), (409, 503), (409, OSError('offline'))):
            with self.subTest(cancel=cancel_status, release=release_status), \
                    tempfile.TemporaryDirectory() as tmp, patch.object(ba, 'log'):
                out = Path(tmp, 'r.csv')
                item = ba.MediaFile(touch(tmp, 'a.mp4'), 'a.mp4', 16)
                state = ba.RunState(out, tmp)
                state.put(item.rel, {'file_id': 'old', 'analysis_id': 'old-analysis'})
                api = Mock()
                api.cancel.return_value = ba.Response(cancel_status, {}, None)
                if isinstance(release_status, Exception):
                    api.release.side_effect = release_status
                else:
                    api.release.return_value = ba.Response(release_status, {}, None)
                rows = []
                ba.Batch(api, ba.Settings(), state=state).run([item], rows.append)
                self.assertTrue(rows[0]['blad'].startswith('[CLEANUP_PENDING]'))
                api.request.assert_not_called()
                self.assertEqual('old', ba.RunState(out, tmp).get(item.rel)['file_id'])

    def test_safe_create_exhaustion_releases_upload_after_final_row(self):
        api = Mock(upload_timeout=900)
        api.request.side_effect = [ba.Response(200, {}, {'fileId': 'file'}), ba.Response(503, {}, None)]
        api.release.return_value = ba.Response(204, {}, None)
        with tempfile.TemporaryDirectory() as tmp, patch.object(ba, 'log'):
            item = ba.MediaFile(touch(tmp, 'a.mp4'), 'a.mp4', 16)
            batch = ba.Batch(api, ba.Settings(max_retries=0))
            batch.run([item], lambda result: api.release.assert_not_called())
            api.release.assert_called_once_with('file', ba.CANCEL_TIMEOUT)
            self.assertEqual({}, batch.state.get(item.rel))

    def test_uncertain_create_is_journalled_and_not_released(self):
        api = Mock(upload_timeout=900)
        api.request.side_effect = [ba.Response(200, {}, {'fileId': 'file'}), TimeoutError('read'),
                                   ba.Response(200, {}, {'content': [], 'page': {'totalPages': 0}})]
        with tempfile.TemporaryDirectory() as tmp, patch.object(ba, 'log'):
            out = Path(tmp, 'r.csv')
            item = ba.MediaFile(touch(tmp, 'a.mp4'), 'a.mp4', 16)
            batch = ba.Batch(api, ba.Settings(), state=ba.RunState(out, tmp))
            rows = []
            batch.run([item], rows.append)
            self.assertTrue(rows[0]['blad'].startswith('[START_UNCERTAIN]'))
            api.release.assert_not_called()
            self.assertTrue(ba.RunState(out, tmp).get(item.rel)['creating'])


class RetryTest(unittest.TestCase):
    def test_retry_after_header(self):
        self.assertEqual(7.0, ba.retry_after({'Retry-After': '7'}))
        self.assertEqual(300.0, ba.retry_after({'Retry-After': '86400'}))
        self.assertEqual(1.0, ba.retry_after({'Retry-After': '-3'}))
        self.assertEqual(1.0, ba.retry_after({'Retry-After': '0'}))
        self.assertEqual(1.0, ba.retry_after({'Retry-After': formatdate(900, usegmt=True)}, now=1000))
        self.assertIsNone(ba.retry_after({}))
        self.assertIsNone(ba.retry_after(None))
        self.assertIsNone(ba.retry_after({'Retry-After': 'soon'}))
        self.assertIsNone(ba.retry_after({'Retry-After': 'nan'}))
        self.assertAlmostEqual(30.0, ba.retry_after({'Retry-After': formatdate(1030, usegmt=True)}, now=1000))

    def test_missing_retry_after_backs_off_to_a_cap(self):
        self.assertEqual([5, 10, 20, 40, 60, 60], [ba.retry_delay({}, n) for n in range(6)])
        self.assertEqual(2.0, ba.retry_delay({'Retry-After': '2'}, 4))

    def test_transient_failures_are_bounded_and_rate_limits_are_not(self):
        responses = iter([ba.Response(429, {}, None)] * 3
                         + [ba.Response(503, {'Retry-After': '5'}, {'code': 'SERVICE_UNAVAILABLE',
                                                                    'message': 'busy'})] * 9)

        class Stub:
            def request(self, *args):
                return next(responses)

        delays = []
        batch = ba.Batch(Stub(), ba.Settings(max_retries=8), wait=lambda s: delays.append(s))
        with patch.object(ba, 'log'), self.assertRaises(ba.FileError) as raised:
            batch.call('x', 'GET', '/')
        self.assertEqual((ba.ERROR, 'SERVICE_UNAVAILABLE'), (raised.exception.status, raised.exception.code))
        self.assertEqual([5, 10, 20] + [5.0] * 8, delays)

    def test_uploads_are_paced_to_the_gateway_bucket(self):
        delays = []
        batch = ba.Batch(None, ba.Settings(), wait=lambda s: delays.append(s))
        with patch.object(ba.time, 'monotonic', side_effect=[100.0, 100.0, 103.0]):
            for _ in range(3):
                batch.pace_upload()
        self.assertEqual([6.0, 9.0], delays)

    def test_network_errors_become_a_file_error(self):
        class Down:
            def request(self, *args):
                raise ConnectionRefusedError('refused')

        batch = ba.Batch(Down(), ba.Settings(max_retries=2), wait=lambda s: False)
        with patch.object(ba, 'log'), self.assertRaises(ba.FileError) as raised:
            batch.call('x', 'GET', '/')
        self.assertEqual('NETWORK_ERROR', raised.exception.code)

    def test_http_error_classification(self):
        rejected = ba.http_error(ba.Response(422, {}, {'code': 'INVALID_FILE', 'message': '[MEDIA_TOO_LONG] long'}))
        self.assertEqual((ba.REJECTED, 'MEDIA_TOO_LONG', '[MEDIA_TOO_LONG] long'),
                         (rejected.status, rejected.code, rejected.text))
        plain = ba.http_error(ba.Response(413, {}, {'code': 'PAYLOAD_TOO_LARGE', 'message': 'Za duży.'}))
        self.assertEqual('[PAYLOAD_TOO_LARGE] Za duży.', plain.text)
        self.assertEqual('[HTTP_500] HTTP 500', ba.http_error(ba.Response(500, {}, None)).text)
        for response in (ba.Response(409, {}, {'code': 'STORAGE_QUOTA_EXCEEDED', 'message': 'Limit.'}),
                         ba.Response(403, {}, None)):
            with self.subTest(status=response.status), self.assertRaises(ba.FatalError):
                ba.http_error(response)


class FakeBackend:
    """Token endpoint and the upload/analysis REST API, with the limits the tool must wait out."""

    def __init__(self):
        self.lock = threading.Lock()
        self.tokens = 0
        self.uploads = []
        self.files = {}
        self.released = []
        self.analyses = {}
        self.calls = {'upload': 0, 'create': 0}
        self.expired = set()
        self.grants = []  # (grant_type, client_id) per token request
        self.device_codes = 0
        self.device_answers = []  # token endpoint errors before the user confirms the code
        self.refresh_works = True
        self.hold_slow = True  # files named *slow* stay PROCESSING until released
        self.held = threading.Event()  # set once a held analysis is being polled
        self.cancelled = []
        self.lost_create_response = False
        backend = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def send(self, status, body=None, headers=None):
                raw = b'' if body is None else json.dumps(body).encode()
                self.send_response(status)
                for name, value in (headers or {}).items():
                    self.send_header(name, value)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def body(self):
                return self.rfile.read(int(self.headers.get('Content-Length') or 0))

            def authorized(self):
                token = (self.headers.get('Authorization') or '').removeprefix('Bearer ')
                if not token.startswith('token-') or token in backend.expired:
                    self.send(401)
                    return False
                return True

            def do_POST(self):
                payload = self.body()
                if self.path == '/realms/deepfake/protocol/openid-connect/auth/device':
                    form = parse_qs(payload.decode())
                    assert form['client_id'] == ['deepfake-cli'], form
                    with backend.lock:
                        backend.device_codes += 1
                        code = backend.device_codes
                    return self.send(200, {
                        'device_code': f'device-{code}', 'user_code': 'WXYZ-ABCD',
                        'verification_uri': backend.url + '/realms/deepfake/device',
                        'verification_uri_complete': backend.url + '/realms/deepfake/device?user_code=WXYZ-ABCD',
                        'expires_in': 600, 'interval': 0})
                if self.path == '/realms/deepfake/protocol/openid-connect/token':
                    return self.token(parse_qs(payload.decode()))
                if not self.authorized():
                    return None
                if self.path == '/api/files/upload':
                    return self.upload(payload)
                if self.path == '/api/analysis':
                    return self.create(json.loads(payload))
                return self.send(404)

            def token(self, form):
                grant, client = form['grant_type'][0], form['client_id'][0]
                with backend.lock:
                    backend.grants.append((grant, client))
                    if grant == 'client_credentials':
                        assert client == 'deepfake-loadtest' and form['client_secret'], form
                    elif grant == ba.DEVICE_GRANT:
                        assert client == 'deepfake-cli' and form['device_code'][0].startswith('device-')
                        if backend.device_answers:
                            return self.send(400, {'error': backend.device_answers.pop(0)})
                    elif grant == 'refresh_token':
                        assert form['refresh_token'][0].startswith('refresh-'), form
                        if not backend.refresh_works:
                            return self.send(400, {'error': 'invalid_grant'})
                    backend.tokens += 1
                    token = f'token-{backend.tokens}'
                body = {'access_token': token, 'expires_in': 300}
                if grant != 'client_credentials':
                    body.update(refresh_token=f'refresh-{backend.tokens}', refresh_expires_in=1800)
                return self.send(200, body)

            def do_DELETE(self):
                if not self.authorized():
                    return None
                analysis_id = self.path.rsplit('/', 1)[-1]
                with backend.lock:
                    if self.path.startswith('/api/files/'):
                        name = backend.files.pop(analysis_id, None)
                        if name is None:
                            return self.send(404)
                        backend.released.append(name)
                        return self.send(204)
                    analysis = backend.analyses.get(analysis_id)
                    if analysis is None:
                        return self.send(404)
                    backend.cancelled.append(analysis['name'])
                    analysis['cancelled'] = True
                return self.send(200, {'id': analysis_id, 'status': 'CANCELLED'})

            def upload(self, payload):
                name = re.search(rb'filename="([^"]+)"', payload).group(1).decode()
                with backend.lock:
                    backend.calls['upload'] += 1
                    if backend.calls['upload'] == 1:
                        return self.send(429, {'code': 'UPLOAD_USER_LIMIT', 'message': 'wait'},
                                         {'Retry-After': '2'})
                    backend.uploads.append(name)
                if name == 'broken.wav':
                    return self.send(422, {'code': 'INVALID_FILE', 'message': '[UNSUPPORTED_MEDIA] Unsupported.'})
                mime = 'audio/vnd.wave' if name.endswith(('.wav', '-named.mp4')) else (
                    'audio/x-flac' if name.endswith('.flac') else 'video/quicktime')
                file_id = str(uuid.uuid4())
                with backend.lock:
                    backend.analyses[file_id] = {'name': name}
                    backend.files[file_id] = name
                return self.send(200, {'fileId': file_id, 'fileKey': file_id + '.bin', 'size': 16, 'mimetype': mime})

            def create(self, request):
                with backend.lock:
                    backend.calls['create'] += 1
                    if backend.calls['create'] == 1:
                        return self.send(429)  # no Retry-After and no body: the backoff path
                    analysis = backend.analyses.pop(request['fileId'])
                    analysis_id = str(uuid.uuid4())
                    backend.analyses[analysis_id] = {**analysis, 'type': request['type'], 'polls': 0,
                                                     'fileId': request['fileId'], 'id': analysis_id}
                    if backend.lost_create_response:
                        return self.send(502)
                return self.send(201, {'id': analysis_id, 'status': 'PENDING', 'type': request['type']})

            def do_GET(self):
                if not self.authorized():
                    return None
                if self.path.startswith('/api/analysis?'):
                    with backend.lock:
                        content = [dict(a) for a in backend.analyses.values() if 'id' in a]
                    return self.send(200, {'content': content, 'page': {'totalPages': 1}})
                analysis_id = self.path.rsplit('/', 1)[-1]
                with backend.lock:
                    analysis = backend.analyses.get(analysis_id)
                    if analysis is None:
                        return self.send(404)
                    analysis['polls'] += 1
                    polls = analysis['polls']
                name, kind = analysis['name'], analysis['type']
                body = {'id': analysis_id, 'type': kind, 'status': 'PROCESSING', 'verdict': None,
                        'videoProb': None, 'audioProb': None, 'errorMessage': None}
                if analysis.get('cancelled'):
                    return self.send(200, {**body, 'status': 'CANCELLED'})
                if polls == 1:
                    return self.send(200, body)
                if 'slow' in name and backend.hold_slow:
                    backend.held.set()
                    return self.send(200, body)
                if 'noface' in name:
                    return self.send(200, {**body, 'status': 'FAILED',
                                           'errorMessage': '[NO_FACE_DETECTED] Nie wykryto twarzy w nagraniu.'})
                fake = 'deep' in name
                prob = 0.91 if fake else 0.12
                return self.send(200, {**body, 'status': 'COMPLETED', 'verdict': 'FAKE' if fake else 'REAL',
                                       'videoProb': None if kind == 'AUDIO' else prob,
                                       'audioProb': prob if kind != 'VIDEO' else None})

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class EndToEndTest(unittest.TestCase):
    files = ('fake/deep1.mp4', 'fake/clip2.mp4', 'fake/noface.mp4', 'real/a.wav', 'real/deep_b.flac',
             'real/speech-named.mp4', 'real/broken.wav', 'other/x.mov', 'notes.txt', '.hidden.mp4')

    def setUp(self):
        self.backend = FakeBackend()
        self.addCleanup(self.backend.close)
        # Interrupted runs leave daemon workers that may still hold a media file open (Windows).
        tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name, 'media')
        for rel in self.files:
            touch(self.root, rel, size=64 * 1024)
        self.out = Path(tmp.name, 'results.csv')
        self.metrics = Path(tmp.name, 'metrics.json')
        self.delays = []
        self.log = patch.object(ba, 'log')
        self.log.start()
        self.addCleanup(self.log.stop)

    def run_cli(self, *extra, secret='secret'):
        args = [str(self.root), '--out', str(self.out), '--gateway', self.backend.url,
                '--keycloak', self.backend.url, '--poll-interval', '0', '--upload-interval', '0', '--timeout', '30', *extra]
        if secret:
            args += ['--client-secret', secret]
        stdout = io.StringIO()
        with redirect_stdout(stdout), patch.dict(ba.os.environ, {}, clear=False) as env:
            for name in ('BATCH_CLIENT_ID', 'BATCH_CLIENT_SECRET'):
                env.pop(name, None)
            code = ba.main(args, wait=lambda s: self.delays.append(s))
        return code, stdout.getvalue()

    def rows(self):
        with self.out.open(newline='', encoding='utf-8') as f:
            return {r['plik']: r for r in csv.DictReader(f)}

    def test_upload_analyse_poll_resume_and_metrics(self):
        code, stdout = self.run_cli('--labels-from-folders', '--metrics-out', str(self.metrics))
        self.assertEqual(0, code)
        rows = self.rows()
        self.assertEqual(sorted(self.files[:8]), sorted(rows))
        self.assertEqual(('FULL', 'COMPLETED', 'FAKE', '0.91', '0.91', ''),
                         tuple(rows['fake/deep1.mp4'][k] for k in ('typ', 'status', 'werdykt', 'prob_wideo',
                                                                   'prob_audio', 'blad')))
        self.assertEqual(('AUDIO', 'REAL', ''), (rows['real/a.wav']['typ'], rows['real/a.wav']['werdykt'],
                                                 rows['real/a.wav']['prob_wideo']))
        # Detected MIME, not the extension, picks the type (frontend rule).
        self.assertEqual('AUDIO', rows['real/speech-named.mp4']['typ'])
        self.assertEqual(('FAILED', '', '[NO_FACE_DETECTED] Nie wykryto twarzy w nagraniu.'),
                         tuple(rows['fake/noface.mp4'][k] for k in ('status', 'werdykt', 'blad')))
        self.assertEqual(('REJECTED', '[UNSUPPORTED_MEDIA] Unsupported.'),
                         (rows['real/broken.wav']['status'], rows['real/broken.wav']['blad']))
        self.assertTrue(all(float(r['czas_s']) >= 0 for r in rows.values()))
        # Upload 429 honours Retry-After; the gateway's bare 429 on create backs off from 5 s.
        self.assertEqual([2.0, 5.0], sorted(d for d in self.delays if d))
        self.assertEqual(1, self.backend.tokens)

        metrics = json.loads(self.metrics.read_text(encoding='utf-8'))
        self.assertEqual({'TP': 1, 'FP': 1, 'TN': 2, 'FN': 1}, metrics['confusion_matrix'])
        self.assertEqual({'NO_FACE_DETECTED': 1, 'UNSUPPORTED_MEDIA': 1}, metrics['excluded_by_code'])
        self.assertEqual(['other/x.mov'], metrics['unlabeled_files'])
        self.assertAlmostEqual(0.6, metrics['accuracy'])
        self.assertIn('TP = 1', stdout)

        uploads = len(self.backend.uploads)
        self.assertEqual((0, uploads), (self.run_cli()[0], len(self.backend.uploads)))
        self.assertEqual(0, self.run_cli('--retry-failed')[0])
        self.assertEqual(sorted(self.backend.uploads[uploads:]), ['broken.wav', 'noface.mp4'])
        self.assertEqual(['noface.mp4'], self.backend.released)
        self.assertEqual(8, len(self.rows()))
        with self.out.open(encoding='utf-8') as f:
            self.assertEqual(9, len(f.read().splitlines()))

        stdout = io.StringIO()
        with redirect_stdout(stdout):
            self.assertEqual(0, ba.main(['--metrics-only', '--out', str(self.out)]))
        self.assertIn('NO_FACE_DETECTED: 1', stdout.getvalue())

    def test_without_a_secret_the_user_signs_in_with_the_device_flow(self):
        self.backend.device_answers = ['authorization_pending']
        self.assertEqual(0, self.run_cli(secret=None)[0])
        self.assertEqual(8, len(self.rows()))
        self.assertEqual({'deepfake-cli'}, {client for _, client in self.backend.grants})
        self.assertEqual(1, self.backend.device_codes)

    def test_failed_sign_in_ends_the_run_before_any_upload(self):
        self.backend.device_answers = ['expired_token']
        self.assertEqual(3, self.run_cli(secret=None)[0])
        self.assertEqual([], self.backend.uploads)

    def test_resume_refuses_another_root_with_the_same_relative_paths(self):
        self.assertEqual(0, self.run_cli()[0])
        before = self.out.read_bytes()
        self.root = self.root.parent / 'other-media'
        touch(self.root, 'fake/deep1.mp4')
        self.assertEqual(2, self.run_cli()[0])
        self.assertEqual(before, self.out.read_bytes())

    def test_metrics_ignore_rows_for_removed_files(self):
        self.assertEqual(0, self.run_cli()[0])
        (self.root / 'fake/deep1.mp4').unlink()
        code, _ = self.run_cli('--labels-from-folders', '--metrics-out', str(self.metrics))
        self.assertEqual(0, code)
        metrics = json.loads(self.metrics.read_text(encoding='utf-8'))
        self.assertEqual(7, metrics['files'])
        self.assertEqual(1, metrics['stale_rows_ignored'])
        self.assertEqual(0, metrics['confusion_matrix']['TP'])
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, ba.main(['--metrics-only', '--out', str(self.out),
                                         '--metrics-out', str(self.metrics)]))
        self.assertEqual(7, json.loads(self.metrics.read_text(encoding='utf-8'))['files'])

    def test_lost_create_response_tracks_the_committed_analysis(self):
        self.backend.lost_create_response = True
        self.assertEqual(0, self.run_cli()[0])
        self.assertEqual(8, len(self.rows()))
        self.assertEqual(8, self.backend.calls['create'])
        self.assertEqual(7, len([a for a in self.backend.analyses.values() if 'id' in a]))

    def test_expired_token_is_renewed_once(self):
        self.backend.expired.add('token-1')
        self.assertEqual(0, self.run_cli()[0])
        self.assertEqual(2, self.backend.tokens)

    def test_token_is_renewed_before_expiry(self):
        now = [0.0]
        tokens = ba.TokenSource(self.backend.url + '/realms/deepfake/protocol/openid-connect/token',
                                'deepfake-loadtest', 'secret', clock=lambda: now[0])
        self.assertEqual('token-1', tokens.get())
        now[0] = 239.0
        self.assertEqual('token-1', tokens.get())
        now[0] = 240.0
        self.assertEqual('token-2', tokens.get())

    def test_ctrl_c_keeps_written_rows_and_resume_finishes(self):
        def interrupt(message):
            if message.startswith('[1/'):
                raise KeyboardInterrupt

        with patch.object(ba, 'log', side_effect=interrupt):
            self.assertEqual(130, self.run_cli('--concurrency', '1')[0])
        self.assertEqual(1, len(self.rows()))
        self.assertEqual(0, self.run_cli()[0])
        self.assertEqual(8, len(self.rows()))

    def interrupt_with_slow_analysis_in_flight(self, *extra):
        touch(self.root, 'fake/slow.mp4', size=64 * 1024)
        messages = []

        def interrupt(message):
            messages.append(message)
            # Interrupt after a row is written, while the slow analysis is still on the server.
            if message.startswith('[1/'):
                self.assertTrue(self.backend.held.wait(10))
                raise KeyboardInterrupt

        with patch.object(ba, 'log', side_effect=interrupt):
            self.assertEqual(130, self.run_cli('--concurrency', '9', *extra)[0])
        return messages

    def test_ctrl_c_cancels_analyses_in_flight_and_resume_reruns_them(self):
        messages = self.interrupt_with_slow_analysis_in_flight()
        self.assertIn('slow.mp4', self.backend.cancelled)
        self.assertIn('slow.mp4', self.backend.released)
        self.assertTrue(any(m.startswith('Anulowano:') for m in messages), messages)
        self.assertNotIn('fake/slow.mp4', self.rows())
        self.backend.hold_slow = False
        uploads = len(self.backend.uploads)
        self.assertEqual(0, self.run_cli()[0])
        self.assertIn('slow.mp4', self.backend.uploads[uploads:])
        self.assertEqual('COMPLETED', self.rows()['fake/slow.mp4']['status'])

    def test_no_cancel_leaves_analyses_running(self):
        messages = self.interrupt_with_slow_analysis_in_flight('--no-cancel')
        self.assertEqual([], self.backend.cancelled)
        self.assertEqual([], self.backend.released)
        self.assertTrue(any(m.startswith('Przerwano') and 'kończą się na serwerze' in m for m in messages))

    def test_cancel_uses_the_cached_token_and_tolerates_failures(self):
        tokens = ba.TokenSource(self.backend.url + '/realms/deepfake/protocol/openid-connect/token',
                                'deepfake-loadtest', 'secret')
        api = ba.Api(self.backend.url, tokens)
        batch = ba.Batch(api, ba.Settings())
        batch.active.update({'missing-1', 'missing-2'})
        batch.cancel_active(timeout=2)  # no token yet: 401, nothing renewed, no exception
        self.assertEqual(0, self.backend.tokens)
        self.assertEqual(401, api.cancel('missing-1').status)

    def test_per_file_timeout_is_recorded_as_a_retryable_error(self):
        code, _ = self.run_cli('--timeout', '0.000001', '--poll-interval', '1')
        self.assertEqual(0, code)
        rows = self.rows()
        timed_out = [r for r in rows.values() if r['status'] == 'ERROR']
        self.assertEqual(7, len(timed_out))
        self.assertTrue(all(r['blad'].startswith('[CLIENT_TIMEOUT]') for r in timed_out))
        self.assertEqual(['real/broken.wav'], list(ba.plan_resume(rows.values())))
        self.assertEqual(7, len(self.backend.cancelled))
        self.assertEqual(7, len(self.backend.released))


class DeviceLoginTest(unittest.TestCase):
    def setUp(self):
        self.backend = FakeBackend()
        self.addCleanup(self.backend.close)
        self.now = [0.0]
        self.sleeps = []
        self.messages = []
        patcher = patch.object(ba, 'log', side_effect=self.messages.append)
        patcher.start()
        self.addCleanup(patcher.stop)

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now[0] += seconds

    def login(self):
        return ba.DeviceLogin(self.backend.url + '/realms/deepfake/protocol/openid-connect/token',
                              'deepfake-cli', clock=lambda: self.now[0], sleep=self.sleep)

    def test_polls_until_authorized_and_honours_slow_down(self):
        self.backend.device_answers = ['authorization_pending', 'slow_down', 'authorization_pending']
        self.assertEqual('token-1', self.login().get())
        # Interval 0 from the server; slow_down adds 5 s for the remaining polls (RFC 8628).
        self.assertEqual([0.0, 0.0, 5.0, 5.0], self.sleeps)
        self.assertIn(self.backend.url + '/realms/deepfake/device?user_code=WXYZ-ABCD', self.messages[0])
        self.assertIn('WXYZ-ABCD', self.messages[1])
        self.assertFalse([m for m in self.messages if 'token-' in m or 'refresh-' in m or 'device-' in m])

    def test_expired_or_denied_code_is_fatal(self):
        for answer in ('expired_token', 'access_denied'):
            with self.subTest(answer=answer):
                self.backend.device_answers = ['authorization_pending', answer]
                with self.assertRaises(ba.FatalError):
                    self.login().get()
        self.assertEqual(0, self.backend.tokens)

    def test_code_lifetime_bounds_the_wait(self):
        self.backend.device_answers = ['authorization_pending'] * 1000
        tokens = self.login()
        tokens.sleep = lambda s: self.sleep(100)
        with self.assertRaises(ba.FatalError):
            tokens.get()
        self.assertLessEqual(len(self.sleeps), 7)

    def test_refresh_before_expiry_then_sign_in_again_when_the_session_ended(self):
        tokens = self.login()
        self.assertEqual('token-1', tokens.get())
        self.now[0] = 239.0
        self.assertEqual('token-1', tokens.get())
        self.now[0] = 240.0
        self.assertEqual('token-2', tokens.get())
        self.assertEqual(('refresh_token', 'deepfake-cli'), self.backend.grants[-1])
        self.assertEqual(1, self.backend.device_codes)
        self.backend.refresh_works = False
        self.now[0] = 500.0
        self.assertEqual('token-3', tokens.get())
        self.assertEqual(2, self.backend.device_codes)
        self.assertEqual(ba.DEVICE_GRANT, self.backend.grants[-1][0])

    def test_rejected_access_token_is_refreshed(self):
        tokens = self.login()
        self.backend.expired.add('token-1')
        api = ba.Api(self.backend.url, tokens)
        self.assertEqual(404, api.request('GET', '/api/analysis/unknown').status)
        self.assertEqual(['token-2'], [tokens.current()])
        self.assertEqual(1, self.backend.device_codes)

    def test_failed_relogin_is_shared_by_all_workers(self):
        tokens = self.login()
        tokens.get()
        self.now[0] = 500
        self.backend.refresh_works = False
        self.backend.device_answers = ['expired_token'] * 3
        failures = []

        def request_token():
            try:
                tokens.get()
            except ba.FatalError as error:
                failures.append(str(error))

        threads = [threading.Thread(target=request_token) for _ in range(3)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(5)
            self.assertFalse(thread.is_alive())
        self.assertEqual(3, len(failures))
        self.assertEqual(2, self.backend.device_codes)


class CreateRecoveryTest(unittest.TestCase):
    def test_uncertain_create_recovers_by_file_id_on_later_history_page(self):
        for failure in (ba.Response(502, {}, None), ba.Response(504, {}, None),
                        TimeoutError('read'), URLError(ConnectionResetError('reset'))):
            with self.subTest(failure=failure):
                api = Mock()
                calls = []

                def request(method, path, *args, calls=calls, failure=failure):
                    calls.append((method, path))
                    if method == 'POST':
                        if isinstance(failure, Exception):
                            raise failure
                        return failure
                    if 'page=0' in path:
                        return ba.Response(200, {}, {'content': [{'id': 'unrelated', 'fileId': 'other'}],
                                                     'page': {'totalPages': 2}})
                    return ba.Response(200, {}, {'content': [{'id': 'recovered', 'fileId': 'file'}],
                                                 'page': {'totalPages': 2}})

                api.request.side_effect = request
                batch = ba.Batch(api, ba.Settings(), wait=lambda s: False)
                response = batch.call('create', 'POST', '/api/analysis', b'{"fileId":"file"}')
                self.assertEqual('recovered', response.body['id'])
                self.assertEqual(1, sum(method == 'POST' for method, _ in calls))

    def test_uncertain_create_without_a_match_does_not_repeat_post(self):
        api = Mock()
        api.request.side_effect = [TimeoutError('read'),
                                   ba.Response(200, {}, {'content': [], 'page': {'totalPages': 0}})]
        batch = ba.Batch(api, ba.Settings(), wait=lambda s: False)
        with self.assertRaises(ba.FileError) as error:
            batch.call('create', 'POST', '/api/analysis', b'{"fileId":"file"}')
        self.assertEqual('START_UNCERTAIN', error.exception.code)
        self.assertEqual(2, api.request.call_count)

    def test_safe_create_failures_may_retry(self):
        for failure in (ba.Response(429, {}, None), ba.Response(503, {}, None),
                        URLError(ConnectionRefusedError('refused'))):
            with self.subTest(failure=failure), patch.object(ba, 'log'):
                api = Mock()
                api.request.side_effect = [failure, ba.Response(201, {}, {'id': 'new'})]
                batch = ba.Batch(api, ba.Settings(), wait=lambda s: False)
                self.assertEqual('new', batch.call('create', 'POST', '/api/analysis',
                                                   b'{"fileId":"file"}').body['id'])
                self.assertEqual(2, api.request.call_count)


if __name__ == '__main__':
    unittest.main(verbosity=2)
