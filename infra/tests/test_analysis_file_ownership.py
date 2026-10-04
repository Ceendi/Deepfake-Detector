"""PR03 acceptance against real JWT validation, file-service, storage, DB and RabbitMQ.

Run: python3 infra/tests/test_analysis_file_ownership.py (Docker Compose required).
Only the generated project is paused/stopped/removed. No ML workers are started.
"""

import base64
import io
import json
import subprocess
import tempfile
import time
import unittest
import uuid
import wave
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request

from test_discovery import ROOT, HTTP, command, resolved_compose, available_test_ports


class AnalysisFileOwnershipTest(unittest.TestCase):
    def compose(self, *args):
        # The explicit name wins over inherited COMPOSE_PROJECT_NAME, including during cleanup.
        return command('docker', 'compose', '--project-name', self.project,
                       '-f', str(self.compose_file), *args)

    def request(self, base, path, token=None, body=None, method=None, headers=None):
        headers = dict(headers or {})
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        with HTTP.open(Request(base + path, data=body, method=method, headers=headers), timeout=10) as response:
            data = response.read()
            return response.status, json.loads(data) if data else None

    def eventually(self, check, timeout=240):
        deadline = time.monotonic() + timeout
        while True:
            try:
                return check()
            except (URLError, AssertionError, OSError, subprocess.CalledProcessError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(2)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='analysis-ownership-')
        self.addCleanup(self.directory.cleanup)
        directory = Path(self.directory.name)
        self.project = project = 'analysis-ownership-' + uuid.uuid4().hex[:12]
        self.compose_file = directory / 'compose.json'
        config = resolved_compose()
        names = ('postgres', 'redis', 'rabbitmq', 'seaweedfs', 'seaweedfs-init',
                 'seaweedfs-bucket-init', 'file-service', 'orchestrator', 'keycloak', 'keycloak-db')
        services = {name: config['services'][name] for name in names}
        ports = dict(zip(('file-service', 'orchestrator', 'keycloak', 'rabbitmq'), available_test_ports(4)))
        targets = {'file-service': 8081, 'orchestrator': 8082, 'keycloak': 8080, 'rabbitmq': 15672}
        issuer = 'http://keycloak:8080/realms/ownership-test'
        for name, service in services.items():
            service.pop('profiles', None)
            service['restart'] = 'no'
            service.pop('ports', None)
            if name in ports:
                service['ports'] = [{'target': targets[name], 'published': str(ports[name]), 'host_ip': '127.0.0.1'}]
            service.get('depends_on', {}).pop('eureka-server', None)
            if 'build' in service:
                service['image'] = project + '-' + name  # Never retag a developer's application image.
            if name in ('file-service', 'orchestrator'):
                service['environment'].update({
                    'JWT_ISSUER_URI': issuer,
                    'JWK_SET_URI': issuer + '/protocol/openid-connect/certs',
                    'EUREKA_CLIENT_ENABLED': 'false',
                    'OTEL_TRACING_EXPORT_ENABLED': 'false',
                })
        services['file-service']['deploy']['replicas'] = 1
        services['file-service']['environment']['STORAGE_CLEANUP_INITIAL_DELAY_MS'] = '3600000'
        services['orchestrator']['environment'].update({
            'FILE_SERVICE_LOOKUP_TIMEOUT': '1s', 'BACKPRESSURE_MAX_INFLIGHT': '3',
            'RELIABILITY_STUCK_JOB_SCAN_INTERVAL_MS': '3600000',
        })
        realm = directory / 'realm.json'
        clients = []
        accounts = []
        for account in ('alice', 'bob'):
            clients.append({'clientId': account, 'secret': 'disposable-' + account, 'enabled': True,
                            'serviceAccountsEnabled': True, 'protocol': 'openid-connect', 'publicClient': False})
            accounts.append({'username': 'service-account-' + account, 'enabled': True,
                             'serviceAccountClientId': account, 'realmRoles': ['USER']})
        realm.write_text(json.dumps({'realm': 'ownership-test', 'enabled': True,
                                     'roles': {'realm': [{'name': 'USER'}]},
                                     'clients': clients, 'users': accounts}))
        services['keycloak']['volumes'] = [{'type': 'bind', 'source': str(realm),
                                           'target': '/opt/keycloak/data/import/realm.json', 'read_only': True}]
        services['keycloak']['command'] = ['start-dev', '--import-realm']
        services['keycloak']['environment']['KC_HOSTNAME'] = 'http://keycloak:8080'
        self.compose_file.write_text(json.dumps({
            'name': project, 'services': services,
            'volumes': {name: {'name': project + '-' + name} for name in (
                'pgdata', 'redisdata', 'rabbitmqdata', 'seaweeddata', 'seaweedconfig', 'keycloak-pgdata')},
            'networks': {'deepfake': {'name': project + '-network'}},
        }))
        self.addCleanup(self.compose, 'down', '--volumes', '--remove-orphans')
        self.addCleanup(self.failure_logs)
        print('Starting isolated Compose project ' + project, flush=True)
        self.compose('up', '-d', '--build', '--wait', '--wait-timeout', '300')
        self.files = 'http://' + self.compose('port', 'file-service', '8081')
        self.orchestrator = 'http://' + self.compose('port', 'orchestrator', '8082')
        self.identity = 'http://' + self.compose('port', 'keycloak', '8080')
        self.broker = 'http://' + self.compose('port', 'rabbitmq', '15672')
        env = services['rabbitmq']['environment']
        self.broker_headers = {'Authorization': 'Basic ' + base64.b64encode(
            (env['RABBITMQ_DEFAULT_USER'] + ':' + env['RABBITMQ_DEFAULT_PASS']).encode()).decode()}
        self.tokens = {}
        for account in ('alice', 'bob'):
            self.tokens[account] = self.eventually(lambda: self.request(
                self.identity, '/realms/ownership-test/protocol/openid-connect/token',
                body=urlencode({'grant_type': 'client_credentials', 'client_id': account,
                                'client_secret': 'disposable-' + account}).encode(),
                headers={'Content-Type': 'application/x-www-form-urlencoded'},
            ))[1]['access_token']
        self.eventually(lambda: self.request(self.orchestrator, '/api/analysis', self.tokens['alice']))

    def failure_logs(self):
        result = self._outcome.result
        if any(test is self for test, _ in result.failures + result.errors):
            print(self.compose('logs', '--tail', '80', 'file-service', 'orchestrator', 'keycloak'), flush=True)

    def upload(self, account):
        media = io.BytesIO()
        with wave.open(media, 'wb') as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            audio.writeframes(b'\0\0' * 16000)
        boundary = 'test-' + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{account}.wav"\r\n'
                'Content-Type: audio/wav\r\n\r\n').encode() + media.getvalue() + f'\r\n--{boundary}--\r\n'.encode()
        return self.request(self.files, '/api/files/upload', self.tokens[account], body,
                            headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})[1]

    def sql(self, query):
        return self.compose('exec', '-T', 'postgres', 'psql', '-U', 'deepfake', '-d', 'deepfake',
                            '-At', '-v', 'ON_ERROR_STOP=1', '-c', query)

    def snapshot(self):
        rows = self.sql("SELECT count(*), count(*) FILTER (WHERE status IN ('PENDING', 'PROCESSING')) FROM analysis")
        queues = self.request(self.broker, '/api/queues/%2F', headers=self.broker_headers)[1]
        # Query instantaneous queue depth via basic.get with requeue, rather than delayed management statistics.
        tasks = {}
        for queue in ('analysis.audio', 'analysis.video'):
            messages = self.request(self.broker, '/api/queues/%2F/' + queue + '/get',
                                    body={'count': 20, 'ackmode': 'ack_requeue_true', 'encoding': 'auto'},
                                    headers=self.broker_headers)[1]
            tasks[queue] = sorted(message['payload'] for message in messages)
        self.assertTrue(any(q['name'] == 'analysis.audio' for q in queues))
        return rows, tasks

    def rejected(self, file_id, expected=404, legacy=None, extra_headers=None):
        before = self.snapshot()
        body = {'fileId': file_id, 'type': 'FULL'}
        if legacy is not None:
            body['fileKey'] = legacy
        with self.assertRaises(HTTPError) as error:
            self.request(self.orchestrator, '/api/analysis', self.tokens['alice'], body, headers=extra_headers)
        self.assertEqual(expected, error.exception.code)
        payload = json.load(error.exception)
        self.assertEqual('NOT_FOUND' if expected == 404 else 'SERVICE_UNAVAILABLE', payload['code'])
        self.assertEqual(before, self.snapshot(), 'Rejected create wrote a row, consumed capacity or published a task')

    def task(self, queue):
        messages = self.request(self.broker, '/api/queues/%2F/' + queue + '/get',
                                body={'count': 1, 'ackmode': 'ack_requeue_false', 'encoding': 'auto'},
                                headers=self.broker_headers)[1]
        self.assertEqual(1, len(messages))
        return json.loads(messages[0]['payload'])

    def test_authorized_canonical_input_and_failure_boundaries(self):
        alice = self.upload('alice')
        bob = self.upload('bob')
        self.assertNotEqual(alice['fileId'], bob['fileId'])
        canonical = self.request(self.files, '/api/files/' + alice['fileId'] + '/metadata', self.tokens['alice'])[1]
        self.assertEqual(alice['fileKey'], canonical['objectKey'])
        with self.assertRaises(HTTPError) as foreign_metadata:
            self.request(self.files, '/api/files/' + bob['fileId'] + '/metadata', self.tokens['alice'],
                         headers={'X-User-ID': self.jwt_subject('bob')})
        self.assertEqual(404, foreign_metadata.exception.code)
        foreign_metadata.exception.close()
        self.rejected(bob['fileId'], legacy=bob['fileKey'], extra_headers={'X-User-ID': self.jwt_subject('bob')})
        self.rejected(str(uuid.uuid4()))

        # At zero occupancy, dependency timeout and refusal must not mutate anything.
        self.compose('pause', 'file-service')
        try:
            started = time.monotonic()
            self.rejected(alice['fileId'], expected=503, legacy=bob['fileKey'])
            self.assertLess(time.monotonic() - started, 5)
        finally:
            self.compose('unpause', 'file-service')
        self.compose('stop', 'file-service')
        self.rejected(alice['fileId'], expected=503)
        self.compose('start', 'file-service')
        self.eventually(lambda: self.request(self.files, '/api/files/' + alice['fileId'] + '/metadata', self.tokens['alice']))

        # Exercise all dispatch branches; only FULL uses a deliberately foreign legacy key.
        for kind, account in (('AUDIO', 'alice'), ('VIDEO', 'bob'), ('FULL', 'alice')):
            uploaded = alice if account == 'alice' else bob
            body = {'fileId': uploaded['fileId'], 'type': kind}
            if kind == 'FULL':
                body['fileKey'] = bob['fileKey']
            status, analysis = self.request(self.orchestrator, '/api/analysis', self.tokens[account], body)
            self.assertEqual(201, status)
            self.assertEqual(uploaded['fileKey'], analysis['fileKey'])
            stored = self.sql("SELECT file_key FROM analysis WHERE id = '" + analysis['id'] + "'")
            self.assertEqual(uploaded['fileKey'], stored)
            for queue in (['analysis.audio'] if kind == 'AUDIO' else ['analysis.video'] if kind == 'VIDEO'
                          else ['analysis.audio', 'analysis.video']):
                task = self.eventually(lambda: self.task(queue))
                self.assertEqual(analysis['id'], task['analysis_id'])
                self.assertEqual(uploaded['fileKey'], task['file_key'])
                self.assertEqual('deepfake-uploads', task['file_bucket'])
                self.assertNotIn('Authorization', task)
            print('PASS: ' + kind + ' publishes the canonical owned object key', flush=True)
        self.assertEqual('3|3', self.snapshot()[0])
        self.rejected(bob['fileId'])  # Authorization precedes admission even at capacity.
        self.request(self.files, '/api/files/' + alice['fileId'], self.tokens['alice'], method='DELETE')
        self.rejected(alice['fileId'])
        self.assertEqual('3|3', self.snapshot()[0])  # Already accepted work remains accepted after delete.
        print('PASS: two real accounts; foreign/missing/deleted=404; timeout/refusal=503; no rejected side effects', flush=True)
        print('LIMITATION: no detector inference, recovery, outbox or SSE acceptance is performed in PR03', flush=True)

    def jwt_subject(self, account):
        payload = self.tokens[account].split('.')[1]
        return json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))['sub']


if __name__ == '__main__':
    unittest.main(verbosity=2)
