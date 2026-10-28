"""Batch analysis of a media folder through the REST API, with quality metrics.

Standard library only (Python 3.11+). Usage, resume rules and metrics: tools/batch/README.md.

    python tools/batch/batch_analyze.py recordings/ --out results.csv
    python tools/batch/batch_analyze.py celeb-df-test/ --labels-from-folders --out celebdf.csv
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import queue
import re
import sys
import threading
import time
import uuid
from collections import Counter
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from pathlib import Path, PurePosixPath
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

COLUMNS = ['plik', 'typ', 'status', 'werdykt', 'prob_wideo', 'prob_audio', 'blad', 'czas_s']
MIME_BY_EXT = {
    '.mp4': 'video/mp4', '.mov': 'video/quicktime', '.avi': 'video/x-msvideo',
    '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.flac': 'audio/flac',
}
AUDIO_EXTS = {'.wav', '.mp3', '.flac'}
ANALYSIS_TYPES = ('VIDEO', 'AUDIO', 'FULL')
TERMINAL = {'COMPLETED', 'FAILED', 'CANCELLED'}
# Client-side outcomes next to the backend statuses: REJECTED = the API refused the file (4xx),
# ERROR = the tool gave up on a transient problem (timeout, 5xx, network) and retries it on resume.
REJECTED = 'REJECTED'
ERROR = 'ERROR'
KNOWN_STATUSES = TERMINAL | {REJECTED, ERROR}
LABELS = ('real', 'fake')

RETRY_STATUSES = {429, 502, 503, 504}
MAX_UPLOAD_BYTES = 524_288_000  # docs/contracts/rest-api.md: 500 MiB per file
UPLOAD_SLOTS = 2  # UPLOAD_MAX_CONCURRENT_PER_USER default
UPLOAD_INTERVAL = 6.0  # gateway upload bucket default: 10 per minute
BACKOFF_START, BACKOFF_CAP = 5.0, 60.0
RETRY_AFTER_CAP = 300.0
MAX_TRANSIENT_RETRIES = 8
CANCEL_TIMEOUT = 5.0
CODE_RE = re.compile(r'^\[([A-Z0-9_]+)\]')

USER_CLIENT = 'deepfake-cli'  # public client with the device authorization grant
SERVICE_CLIENT = 'deepfake-loadtest'  # dev-realm service account, used with a client secret
DEVICE_GRANT = 'urn:ietf:params:oauth:grant-type:device_code'
DEFAULTS = {
    'gateway': ('BATCH_GATEWAY', 'http://localhost:8080'),
    'keycloak': ('BATCH_KEYCLOAK', 'http://localhost:8180'),
    'realm': ('BATCH_REALM', 'deepfake'),
    'client_id': ('BATCH_CLIENT_ID', None),
    'client_secret': ('BATCH_CLIENT_SECRET', None),
}


class FileError(Exception):
    """One file cannot be analysed; recorded in the CSV and the run continues."""

    def __init__(self, status, code, message='', typ=''):
        super().__init__(message)
        self.status, self.code, self.message, self.typ = status, code, message, typ

    @property
    def text(self):
        message = self.message.strip()
        if CODE_RE.match(message):
            return message
        return f'[{self.code}] {message}'.strip()


class FatalError(Exception):
    """The whole run cannot continue (credentials, storage quota)."""


class Stopped(Exception):
    """Ctrl+C: abandon the current file without writing a row."""


def log(message):
    print(message, file=sys.stderr, flush=True)


# --- Files, labels, CSV ---

@dataclass(frozen=True)
class MediaFile:
    path: Path
    rel: str
    size: int

    @property
    def guessed_type(self):
        return 'AUDIO' if self.path.suffix.lower() in AUDIO_EXTS else 'FULL'


def discover(root):
    """Supported media under root, recursively, sorted by relative path; hidden entries skipped."""
    root = Path(root)
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        # AppleDouble files (._clip.mp4) and VCS folders are not recordings.
        dirnames[:] = sorted(d for d in dirnames if not d.startswith('.'))
        for name in filenames:
            path = Path(dirpath) / name
            if name.startswith('.') or path.suffix.lower() not in MIME_BY_EXT:
                continue
            found.append(MediaFile(path, path.relative_to(root).as_posix(), path.stat().st_size))
    return sorted(found, key=lambda f: f.rel)


def label_for(rel):
    """Nearest ancestor folder named real/fake (case-insensitive), or None."""
    for part in reversed(PurePosixPath(rel.replace('\\', '/')).parts[:-1]):
        if part.lower() in LABELS:
            return part.lower()
    return None


def read_results(path):
    with Path(path).open(newline='', encoding='utf-8-sig') as f:
        reader = csv.DictReader(f)
        if reader.fieldnames != COLUMNS:
            raise ValueError(f'{path}: nagłówek {reader.fieldnames} zamiast {COLUMNS}')
        return list(reader)


def should_retry(row, retry_failed):
    status = row.get('status') or ''
    if status not in KNOWN_STATUSES or status == ERROR:
        return True  # transient give-up or a row cut off by a crash
    return retry_failed and status != 'COMPLETED'


def plan_resume(rows, retry_failed=False):
    """Rows to keep, one per file (the last one wins); files without a kept row are analysed."""
    latest = {}
    for row in rows:
        if row.get('plik'):
            latest[row['plik']] = row
    return {rel: row for rel, row in latest.items() if not should_retry(row, retry_failed)}


def write_results(path, rows):
    # Rewritten through a temporary file so an interrupted rewrite never loses finished rows.
    path = Path(path)
    tmp = path.with_name(path.name + '.tmp')
    with tmp.open('w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, COLUMNS)
        writer.writeheader()
        writer.writerows(rows)
    os.replace(tmp, path)


class RowWriter:
    def __init__(self, file):
        self.file = file
        self.writer = csv.DictWriter(file, COLUMNS)

    def __call__(self, row):
        self.writer.writerow(row)
        self.file.flush()
        os.fsync(self.file.fileno())


# --- HTTP ---

def retry_after(headers, now=None):
    """Seconds from a Retry-After header (delta or HTTP date), or None when absent/invalid."""
    value = headers.get('Retry-After') if headers else None
    if not value:
        return None
    try:
        seconds = float(value.strip())
    except ValueError:
        try:
            when = parsedate_to_datetime(value.strip())
        except (TypeError, ValueError, IndexError):
            return None
        seconds = when.timestamp() - (time.time() if now is None else now)
    if not math.isfinite(seconds):
        return None
    return min(max(seconds, 0.0), RETRY_AFTER_CAP)


def backoff(attempt):
    return min(BACKOFF_START * 2 ** attempt, BACKOFF_CAP)


def retry_delay(headers, attempt):
    # Every limit sends Retry-After; the backoff covers proxies and a gateway Redis outage (503).
    seconds = retry_after(headers)
    return backoff(attempt) if seconds is None else seconds


@dataclass
class Response:
    status: int
    headers: object
    body: object


def _parse(raw):
    if not raw:
        return None
    try:
        return json.loads(raw)
    except ValueError:
        return raw.decode('utf-8', errors='replace')


def post_form(url, fields, timeout=30):
    """POST to Keycloak; returns (status, JSON object). Network errors raise OSError."""
    request = Request(url, data=urlencode(fields).encode(), headers={
        'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json'})
    try:
        with urlopen(request, timeout=timeout) as response:
            status, raw = response.status, response.read()
    except HTTPError as error:
        with error:
            status, raw = error.code, error.read()
    body = _parse(raw)
    return status, body if isinstance(body, dict) else {}


def keycloak_failure(status, hint):
    if status in RETRY_STATUSES or status >= 500:
        return OSError(f'Keycloak HTTP {status}')
    return FatalError(f'Keycloak odrzucił klienta (HTTP {status}): {hint}')


class TokenSource:
    """client_credentials token, renewed before expiry so runs of many hours keep working."""

    def __init__(self, url, client_id, client_secret, clock=time.monotonic):
        self.url, self.client_id, self.client_secret, self.clock = url, client_id, client_secret, clock
        self.lock = threading.Lock()
        self.token = None
        self.renew_at = 0.0

    def get(self):
        with self.lock:
            if self.token is None or self.clock() >= self.renew_at:
                self._fetch()
            return self.token

    def current(self):
        """The cached token without renewing it (may be None or expired)."""
        return self.token

    def invalidate(self, rejected):
        # Only the rejected token: parallel 401s for one expired token trigger one renewal.
        with self.lock:
            if self.token == rejected:
                self.token = None

    def _store(self, data):
        lifetime = float(data.get('expires_in') or 60)
        self.token = data['access_token']
        self.renew_at = self.clock() + max(lifetime - 60, lifetime / 2)

    def _fetch(self):
        status, data = post_form(self.url, {'grant_type': 'client_credentials',
                                            'client_id': self.client_id,
                                            'client_secret': self.client_secret})
        if status != 200:
            raise keycloak_failure(status, 'sprawdź --keycloak, --realm, --client-id i --client-secret.')
        self._store(data)


class DeviceLogin(TokenSource):
    """User sign-in through the OAuth 2.0 device authorization grant (RFC 8628).

    The user confirms a code in a browser, so analyses land in that user's history. The refresh
    token renews the access token; when the session has ended, the user is asked to sign in again.
    """

    def __init__(self, url, client_id, clock=time.monotonic, sleep=time.sleep):
        super().__init__(url, client_id, None, clock)
        self.device_url = url.rsplit('/', 1)[0] + '/auth/device'
        self.sleep = sleep
        self.refresh_token = None

    def _store(self, data):
        super()._store(data)
        self.refresh_token = data.get('refresh_token')

    def _fetch(self):
        if self.refresh_token:
            status, data = post_form(self.url, {'grant_type': 'refresh_token', 'client_id': self.client_id,
                                                'refresh_token': self.refresh_token})
            if status == 200:
                return self._store(data)
            if status in RETRY_STATUSES or status >= 500:
                raise keycloak_failure(status, '')
            self.refresh_token = None
            log('Sesja Keycloak wygasła, zaloguj się ponownie.')
        return self._login()

    def _login(self):
        status, grant = post_form(self.device_url, {'client_id': self.client_id})
        if status != 200 or 'device_code' not in grant:
            raise keycloak_failure(status, f'klient {self.client_id} musi mieć włączony device '
                                           'authorization grant (infra/keycloak/README.md).')
        interval = float(grant.get('interval', 5))
        deadline = self.clock() + float(grant.get('expires_in', 600))
        link = grant.get('verification_uri_complete') or grant.get('verification_uri')
        log(f'Zaloguj się w przeglądarce: {link}')
        log(f'Kod do potwierdzenia: {grant.get("user_code")}. Czekam na logowanie '
            f'(do {deadline - self.clock():.0f} s)...')
        fields = {'grant_type': DEVICE_GRANT, 'device_code': grant['device_code'],
                  'client_id': self.client_id}
        while True:
            self.sleep(interval)
            if self.clock() > deadline:
                raise FatalError('Kod logowania wygasł. Uruchom narzędzie ponownie.')
            try:
                status, data = post_form(self.url, fields)
            except OSError:
                continue  # Keycloak briefly unreachable: keep polling until the code expires
            if status == 200:
                log('Zalogowano.')
                return self._store(data)
            error = data.get('error')
            if error == 'slow_down':
                interval += 5  # RFC 8628 section 3.5
            elif error == 'expired_token':
                raise FatalError('Kod logowania wygasł. Uruchom narzędzie ponownie.')
            elif error == 'access_denied':
                raise FatalError('Logowanie zostało odrzucone w przeglądarce.')
            elif error != 'authorization_pending' and status not in RETRY_STATUSES and status < 500:
                raise FatalError(f'Keycloak odrzucił logowanie (HTTP {status}, {error}).')


class Api:
    def __init__(self, gateway, tokens, timeout=30, upload_timeout=900):
        self.gateway, self.tokens = gateway.rstrip('/'), tokens
        self.timeout, self.upload_timeout = timeout, upload_timeout

    def request(self, method, path, data=None, headers=None, timeout=None, renew=True):
        """One HTTP exchange; a 401 renews the token once. Network errors raise OSError.

        renew=False sends the cached token as is and never contacts Keycloak.
        """
        for attempt in range(2 if renew else 1):
            body = data() if callable(data) else data
            token = (self.tokens.get() if renew else self.tokens.current()) or ''
            request = Request(self.gateway + path, data=body, method=method, headers={
                'Authorization': 'Bearer ' + token, 'Accept': 'application/json', **(headers or {})})
            try:
                with urlopen(request, timeout=timeout or self.timeout) as response:
                    result = Response(response.status, response.headers, _parse(response.read()))
            except HTTPError as error:
                with error:
                    result = Response(error.code, error.headers, _parse(error.read()))
            if result.status != 401 or attempt or not renew:
                return result
            self.tokens.invalidate(token)
        return result

    def cancel(self, analysis_id, timeout=CANCEL_TIMEOUT):
        # Cached token only: an interrupted run must not start a browser login.
        return self.request('DELETE', f'/api/analysis/{quote(analysis_id)}', timeout=timeout, renew=False)


def multipart(path, mime):
    """Streaming multipart body: a 500 MiB upload is never held in memory."""
    boundary = uuid.uuid4().hex
    filename = re.sub(r'["\r\n\\]', '_', Path(path).name)
    head = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f'Content-Type: {mime}\r\n\r\n').encode()
    tail = f'\r\n--{boundary}--\r\n'.encode()
    length = len(head) + Path(path).stat().st_size + len(tail)

    def body():
        yield head
        with Path(path).open('rb') as f:
            while chunk := f.read(1 << 20):
                yield chunk
        yield tail

    headers = {'Content-Type': f'multipart/form-data; boundary={boundary}', 'Content-Length': str(length)}
    return body, headers


def http_error(response, typ=''):
    """FileError for a non-success response; FatalError when no later file can succeed either."""
    body = response.body if isinstance(response.body, dict) else {}
    message = str(body.get('message') or (response.body if isinstance(response.body, str) else '') or '')
    match = CODE_RE.match(message.strip())
    code = match.group(1) if match else (body.get('code') or f'HTTP_{response.status}')
    if response.status in (401, 403):
        raise FatalError(f'HTTP {response.status}: token klienta nie ma dostępu do API '
                         '(JWT_ALLOWED_CLIENTS, rola USER).')
    if body.get('code') == 'STORAGE_QUOTA_EXCEEDED':
        raise FatalError(f'{message} Przetworzone pliki są w CSV; po zwolnieniu miejsca uruchom ponownie.')
    status = ERROR if response.status >= 500 or response.status in (408, 429) else REJECTED
    return FileError(status, code, message or f'HTTP {response.status}', typ)


# --- Batch run ---

@dataclass
class Settings:
    concurrency: int = 3
    timeout: float = 1800.0
    poll_interval: float = 3.0
    type_override: str | None = None
    upload_interval: float = UPLOAD_INTERVAL
    max_retries: int = MAX_TRANSIENT_RETRIES
    cancel_on_interrupt: bool = True


class Batch:
    def __init__(self, api, settings, wait=None):
        self.api, self.settings = api, settings
        self.stop = threading.Event()  # Ctrl+C: abandon everything
        self.drain = threading.Event()  # fatal error: finish files in flight, start no new ones
        self.upload_slots = threading.Semaphore(UPLOAD_SLOTS)
        self.pace_lock = threading.Lock()
        self.next_upload = 0.0
        self.wait = wait or self.stop.wait
        self.active_lock = threading.Lock()
        self.active = set()  # analyses created on the server and not yet seen terminal

    def pause(self, seconds):
        if self.wait(seconds) or self.stop.is_set():
            raise Stopped()

    def pace_upload(self):
        # Staying under the gateway bucket avoids 429s that a large streamed body may only
        # surface as a reset connection.
        with self.pace_lock:
            now = time.monotonic()
            start = max(now, self.next_upload)
            self.next_upload = start + self.settings.upload_interval
        if start > now:
            self.pause(start - now)

    def call(self, what, method, path, data=None, headers=None, timeout=None, typ=''):
        """Request that waits out 429 (without a limit) and retries 5xx/network errors (bounded)."""
        waits = transient = 0
        while True:
            if self.stop.is_set():
                raise Stopped()
            try:
                response = self.api.request(method, path, data, headers, timeout)
            except OSError as error:
                response, reason = None, f'{type(error).__name__}: {error}'
            else:
                if response.status not in RETRY_STATUSES:
                    return response
                reason = f'HTTP {response.status}'
            # 429 is an expected limit (option A: the tool waits); other failures are bounded.
            if response is None or response.status != 429:
                if transient >= self.settings.max_retries:
                    if response is None:
                        raise FileError(ERROR, 'NETWORK_ERROR', f'{what}: {reason}', typ)
                    raise http_error(response, typ)
                transient += 1
            delay = retry_delay(response.headers if response else None, waits)
            waits += 1
            log(f'  {what}: {reason}, ponowienie za {delay:.0f} s')
            self.pause(delay)

    def analyse(self, item):
        typ = self.settings.type_override or item.guessed_type
        if item.size > MAX_UPLOAD_BYTES:
            raise FileError(REJECTED, 'PAYLOAD_TOO_LARGE', 'Plik przekracza 500 MiB.', typ)
        body, headers = multipart(item.path, MIME_BY_EXT[item.path.suffix.lower()])
        with self.upload_slots:
            self.pace_upload()
            response = self.call(f'upload {item.rel}', 'POST', '/api/files/upload', body, headers,
                                 self.api.upload_timeout, typ)
        if response.status not in (200, 201):
            raise http_error(response, typ)
        uploaded = response.body
        # Same rule as the frontend: the server-detected MIME picks the analysis type.
        if not self.settings.type_override:
            typ = 'AUDIO' if str(uploaded.get('mimetype') or '').lower().startswith('audio/') else 'FULL'
        response = self.call(f'analiza {item.rel}', 'POST', '/api/analysis',
                             json.dumps({'fileId': uploaded['fileId'], 'type': typ}).encode(),
                             {'Content-Type': 'application/json'}, typ=typ)
        if response.status != 201:
            raise http_error(response, typ)
        analysis_id = response.body['id']
        with self.active_lock:
            self.active.add(analysis_id)
        try:
            analysis = self.poll(analysis_id, typ)
        except Stopped:
            raise  # stays active: the interrupt handler cancels it on the server
        except Exception:
            self._settled(analysis_id)
            raise
        self._settled(analysis_id)
        return analysis

    def _settled(self, analysis_id):
        with self.active_lock:
            self.active.discard(analysis_id)

    def cancel_active(self, timeout=CANCEL_TIMEOUT):
        """Ctrl+C: best-effort DELETE of analyses still running on the server, in parallel."""
        with self.active_lock:
            ids = sorted(self.active)
        if not ids:
            return
        log(f'Anuluję analizy w toku na serwerze: {len(ids)}...')
        settled = []

        def cancel(analysis_id):
            try:
                response = self.api.cancel(analysis_id, timeout)
            except OSError:
                return
            # 409: it finished first; either way nothing keeps running for this file.
            if response.status in (200, 409):
                settled.append(analysis_id)

        threads = [threading.Thread(target=cancel, args=(i,), daemon=True) for i in ids]
        for thread in threads:
            thread.start()
        deadline = time.monotonic() + timeout + 1
        for thread in threads:
            thread.join(max(0.0, deadline - time.monotonic()))
        missed = len(ids) - len(settled)
        log(f'Anulowano: {len(settled)} z {len(ids)}.' + (
            f' {missed} może nadal trwać na serwerze (anuluj w UI).' if missed else ''))

    def poll(self, analysis_id, typ):
        deadline = time.monotonic() + self.settings.timeout
        waits = 0
        while True:
            if self.stop.is_set():
                raise Stopped()
            delay = self.settings.poll_interval
            try:
                response = self.api.request('GET', f'/api/analysis/{analysis_id}')
            except OSError:
                response = None
            if response is not None:
                if response.status == 200 and response.body.get('status') in TERMINAL:
                    return response.body
                if response.status in (401, 403):
                    raise http_error(response, typ)
                if response.status == 404:
                    raise FileError(ERROR, 'HTTP_404', f'Analiza {analysis_id} nie istnieje.', typ)
                if response.status == 429:
                    delay = max(delay, retry_delay(response.headers, waits))
                    waits += 1
            if time.monotonic() + delay > deadline:
                raise FileError(ERROR, 'CLIENT_TIMEOUT', f'Brak wyniku po {self.settings.timeout:.0f} s '
                                f'(analiza {analysis_id} może nadal trwać).', typ)
            self.pause(delay)

    def process(self, item):
        started = time.monotonic()
        row = dict.fromkeys(COLUMNS, '')
        row.update(plik=item.rel, typ=self.settings.type_override or item.guessed_type)
        try:
            analysis = self.analyse(item)
        except FileError as error:
            row.update(typ=error.typ or row['typ'], status=error.status, blad=error.text)
        else:
            row.update(row_from_analysis(analysis, row['typ']))
        row['czas_s'] = f'{time.monotonic() - started:.1f}'
        return row

    def run(self, items, write, progress=None):
        """Analyses items with `concurrency` workers; returns 0, or 3 after a fatal error."""
        progress = progress or log
        work = queue.Queue()
        for item in items:
            work.put(item)
        results = queue.Queue()

        def worker():
            while not (self.stop.is_set() or self.drain.is_set()):
                try:
                    item = work.get_nowait()
                except queue.Empty:
                    return
                try:
                    results.put(('row', self.process(item)))
                except Stopped:
                    return
                except FatalError as error:
                    self.drain.set()
                    results.put(('fatal', str(error)))
                    return
                except Exception as error:  # noqa: BLE001 - one broken file must not end the run
                    row = dict.fromkeys(COLUMNS, '')
                    row.update(plik=item.rel, typ=item.guessed_type, status=ERROR,
                               blad=f'[CLIENT_ERROR] {type(error).__name__}: {error}')
                    results.put(('row', row))

        threads = [threading.Thread(target=worker, daemon=True)
                   for _ in range(max(1, min(self.settings.concurrency, len(items))))]
        for thread in threads:
            thread.start()
        done, fatal = 0, None
        try:
            while any(t.is_alive() for t in threads) or not results.empty():
                try:
                    kind, payload = results.get(timeout=0.5)
                except queue.Empty:
                    continue
                if kind == 'fatal':
                    if fatal is None:
                        fatal = payload
                        progress(f'BŁĄD: {payload} Kończę pliki w toku, nowych nie zaczynam.')
                    continue
                write(payload)
                done += 1
                progress(f'[{done}/{len(items)}] {payload["plik"]}: {payload["status"]} '
                         f'{payload["werdykt"] or payload["blad"]} ({payload["czas_s"]} s)'.rstrip())
        except KeyboardInterrupt:
            self.stop.set()
            # A short grace lets workers notice the stop and an answered create register its id.
            deadline = time.monotonic() + 1
            for thread in threads:
                thread.join(max(0.0, deadline - time.monotonic()))
            if self.settings.cancel_on_interrupt:
                self.cancel_active()
            raise
        return 3 if fatal else 0


def _number(value):
    return '' if value is None else str(value)


def row_from_analysis(analysis, typ):
    status = analysis.get('status') or ''
    return {
        'typ': analysis.get('type') or typ,
        'status': status,
        'werdykt': (analysis.get('verdict') or '') if status == 'COMPLETED' else '',
        'prob_wideo': _number(analysis.get('videoProb')),
        'prob_audio': _number(analysis.get('audioProb')),
        'blad': analysis.get('errorMessage') or '',
    }


# --- Metrics ---

def error_code(row):
    match = CODE_RE.match((row.get('blad') or '').strip())
    if match:
        return match.group(1)
    status = row.get('status') or ''
    return status if status and status != 'COMPLETED' else 'NO_VERDICT'


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def compute_metrics(rows):
    """Metrics for class FAKE over labelled files with a verdict; the rest is counted by code."""
    latest = {}
    for row in rows:
        if row.get('plik'):
            latest[row['plik']] = row
    matrix = dict.fromkeys(('TP', 'FP', 'TN', 'FN'), 0)
    excluded = Counter()
    by_label = {label: Counter() for label in ('fake', 'real')}
    unlabeled = []
    for rel, row in latest.items():
        label = label_for(rel)
        if label is None:
            unlabeled.append(rel)
            continue
        verdict = (row.get('werdykt') or '').upper()
        if row.get('status') != 'COMPLETED' or verdict not in ('FAKE', 'REAL'):
            code = error_code(row)
            excluded[code] += 1
            by_label[label][code] += 1
            continue
        if label == 'fake':
            matrix['TP' if verdict == 'FAKE' else 'FN'] += 1
        else:
            matrix['FP' if verdict == 'FAKE' else 'TN'] += 1
    tp, fp, tn, fn = matrix['TP'], matrix['FP'], matrix['TN'], matrix['FN']
    recall, specificity = ratio(tp, tp + fn), ratio(tn, tn + fp)
    predicted = tp + fp + tn + fn
    return {
        'positive_class': 'FAKE',
        'files': len(latest),
        'labelled': len(latest) - len(unlabeled),
        'predicted': predicted,
        'confusion_matrix': matrix,
        'precision': ratio(tp, tp + fp),
        'recall': recall,
        # 2TP/(2TP+FP+FN) equals the harmonic mean and stays defined when precision is not.
        'f1': ratio(2 * tp, 2 * tp + fp + fn),
        'accuracy': ratio(tp + tn, predicted),
        'specificity': specificity,
        'balanced_accuracy': None if recall is None or specificity is None else (recall + specificity) / 2,
        'excluded_by_code': dict(sorted(excluded.items())),
        'excluded_by_label': {label: dict(sorted(c.items())) for label, c in by_label.items()},
        'unlabeled': len(unlabeled),
        'unlabeled_files': sorted(unlabeled),
    }


def _fmt(value):
    return 'n/d' if value is None else f'{value:.4f}'


def format_metrics(m):
    cm = m['confusion_matrix']
    lines = [
        f'Metryki (klasa pozytywna FAKE): {m["predicted"]} predykcji z {m["labelled"]} plików z etykietą',
        '                   przewidziane FAKE   przewidziane REAL',
        f'  etykieta fake    TP = {cm["TP"]:<14}FN = {cm["FN"]}',
        f'  etykieta real    FP = {cm["FP"]:<14}TN = {cm["TN"]}',
        (f'precision {_fmt(m["precision"])}  recall {_fmt(m["recall"])}  F1 {_fmt(m["f1"])}  '
         f'accuracy {_fmt(m["accuracy"])}  balanced accuracy {_fmt(m["balanced_accuracy"])}'),
    ]
    if m['excluded_by_code']:
        codes = ', '.join(f'{code}: {count}' for code, count in m['excluded_by_code'].items())
        lines.append(f'Bez predykcji (poza metrykami): {codes}')
    if m['unlabeled']:
        sample = ', '.join(m['unlabeled_files'][:5]) + (' …' if m['unlabeled'] > 5 else '')
        lines.append(f'Bez etykiety real/fake (poza metrykami): {m["unlabeled"]} ({sample})')
    return '\n'.join(lines)


def report_metrics(rows, metrics_out=None):
    metrics = compute_metrics(rows)
    print(format_metrics(metrics))
    if metrics_out:
        Path(metrics_out).write_text(json.dumps(metrics, indent=2, ensure_ascii=False) + '\n',
                                     encoding='utf-8')
    return metrics


# --- CLI ---

def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description='Analizuje wszystkie pliki MP4/MOV/AVI/WAV/MP3/FLAC z folderu przez REST API i '
                    'zapisuje wynik do CSV (wiersz na plik). Ponowne uruchomienie z tym samym --out '
                    'wznawia przerwany przebieg. Szczegóły: tools/batch/README.md.')
    parser.add_argument('folder', nargs='?', help='folder z nagraniami (przeszukiwany rekurencyjnie)')
    parser.add_argument('--out', default='results.csv', help='plik CSV z wynikami (domyślnie %(default)s)')
    parser.add_argument('--labels-from-folders', action='store_true',
                        help='etykieta = najbliższy folder nadrzędny real/fake; liczy metryki jakości')
    parser.add_argument('--metrics-only', action='store_true',
                        help='tylko przelicz metryki z istniejącego --out (bez serwera)')
    parser.add_argument('--metrics-out', help='zapisz metryki do pliku JSON')
    parser.add_argument('--retry-failed', action='store_true',
                        help='przy wznowieniu analizuj ponownie także FAILED, CANCELLED i REJECTED')
    parser.add_argument('--type', choices=ANALYSIS_TYPES, dest='type_override',
                        help='typ analizy dla wszystkich plików (domyślnie AUDIO dla audio, FULL dla wideo)')
    parser.add_argument('--concurrency', type=int, default=3,
                        help='analizy w toku naraz (domyślnie %(default)s = limit aktywnych analiz użytkownika)')
    parser.add_argument('--timeout', type=float, default=1800.0,
                        help='maks. czas oczekiwania na wynik jednej analizy w s (domyślnie %(default).0f)')
    parser.add_argument('--poll-interval', type=float, default=3.0,
                        help='odstęp odpytywania GET /api/analysis/{id} w s (domyślnie %(default).0f)')
    parser.add_argument('--upload-interval', type=float, default=UPLOAD_INTERVAL,
                        help='min. odstęp między uploadami w s (domyślnie %(default).0f = 10 na minutę, '
                             'limit gatewaya; 0 przy podniesionym UPLOAD_RL_*)')
    parser.add_argument('--no-cancel', dest='cancel', action='store_false',
                        help='po Ctrl+C nie anuluj analiz w toku na serwerze')
    helps = {
        'client_id': f'zmienna BATCH_CLIENT_ID, domyślnie {USER_CLIENT} (logowanie w przeglądarce), '
                     f'a z sekretem {SERVICE_CLIENT}',
        'client_secret': 'zmienna BATCH_CLIENT_SECRET; podany = konto serwisowe (client_credentials) '
                         'zamiast logowania użytkownika',
    }
    for name, (env, default) in DEFAULTS.items():
        parser.add_argument('--' + name.replace('_', '-'), default=os.environ.get(env) or default,
                            help=helps.get(name, f'zmienna {env}, domyślnie {default}'))
    args = parser.parse_args(argv)
    if not args.client_id:
        args.client_id = SERVICE_CLIENT if args.client_secret else USER_CLIENT
    if not args.metrics_only and not args.folder:
        parser.error('podaj folder z nagraniami albo --metrics-only')
    if args.concurrency < 1 or args.timeout <= 0 or args.poll_interval < 0 or args.upload_interval < 0:
        parser.error('--concurrency >= 1, --timeout > 0, --poll-interval i --upload-interval >= 0')
    return args


def login(args, sleep=time.sleep):
    """Token source signed in before the first upload, so the browser prompt comes first."""
    token_url = f'{args.keycloak.rstrip("/")}/realms/{quote(args.realm)}/protocol/openid-connect/token'
    if args.client_secret:
        tokens = TokenSource(token_url, args.client_id, args.client_secret)
    else:
        tokens = DeviceLogin(token_url, args.client_id, sleep=sleep)
    tokens.get()
    return tokens


def main(argv=None, api=None, wait=None):
    args = parse_args(argv)
    out = Path(args.out)
    try:
        existing = read_results(out) if out.exists() else []
    except ValueError as error:
        log(f'BŁĄD: {error}. Użyj innego --out.')
        return 2
    if args.metrics_only:
        if not out.exists():
            log(f'BŁĄD: brak pliku {out}.')
            return 2
        report_metrics(existing, args.metrics_out)
        return 0

    root = Path(args.folder)
    if not root.is_dir():
        log(f'BŁĄD: {root} nie jest folderem.')
        return 2
    items = discover(root)
    kept = plan_resume(existing, args.retry_failed)
    todo = [item for item in items if item.rel not in kept]
    log(f'Plików: {len(items)}, z wynikiem w {out}: {len(items) - len(todo)}, do analizy: {len(todo)}')
    write_results(out, kept.values())

    code = 0
    if todo:
        settings = Settings(args.concurrency, args.timeout, args.poll_interval, args.type_override,
                            args.upload_interval, cancel_on_interrupt=args.cancel)
        try:
            if api is None:
                try:
                    api = Api(args.gateway, login(args))
                except (FatalError, OSError) as error:
                    log(f'BŁĄD logowania: {error}')
                    return 3
            batch = Batch(api, settings, wait)
            with out.open('a', newline='', encoding='utf-8') as f:
                code = batch.run(todo, RowWriter(f))
        except KeyboardInterrupt:
            log(f'Przerwano. Zapisane wiersze zostają w {out}; uruchom ponownie, aby wznowić '
                '(przerwane pliki zostaną przeanalizowane od nowa).'
                + ('' if args.cancel else ' Analizy w toku kończą się na serwerze.'))
            return 130
    if args.labels_from_folders:
        report_metrics(read_results(out), args.metrics_out)
    return code


if __name__ == '__main__':
    sys.exit(main())
