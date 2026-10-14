"""Verify least-privilege RabbitMQ users on a disposable broker, fresh and pre-existing.

Run: python3 infra/tests/test_rabbitmq_users.py (Python 3.9+, Docker Compose).
AMQP-level allow/deny checks with the real detector runtime live in tests/worker_redelivery.
"""

import base64
import json
import re
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request

from test_discovery import HTTP, ROOT, available_test_ports, resolved_compose

QUEUES = ("analysis.video", "analysis.audio", "analysis.results", "analysis.progress",
          "analysis.video.dlq", "analysis.audio.dlq", "analysis.results.dlq",
          "analysis.backend.quarantine")


class RabbitMqUsersTest(unittest.TestCase):
    def compose(self, *args, check=True):
        result = subprocess.run(
            ['docker', 'compose', '--project-name', self.project, '-f', str(self.compose_file), *args],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        if check and result.returncode:
            # The model holds generated passwords; keep captured output out of CI logs.
            raise RuntimeError('Isolated RabbitMQ Compose command failed; captured output suppressed')
        return result

    def write_config(self):
        # Quote dollars so generated values stay literal when Compose re-parses the file.
        self.compose_file.write_text(json.dumps(self.config).replace('$', '$$'))
        self.compose_file.chmod(0o600)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='rabbitmq-users-')
        self.addCleanup(self.directory.cleanup)
        self.project = 'rabbitmq-users-' + uuid.uuid4().hex[:12]
        self.compose_file = Path(self.directory.name) / 'compose.json'
        model = resolved_compose()
        services = {name: model['services'][name] for name in ('rabbitmq', 'rabbitmq-init')}
        for service in services.values():
            service.pop('profiles', None)
            service['restart'] = 'no'
            service.pop('ports', None)
        services['rabbitmq']['ports'] = [{'target': 15672, 'published': str(available_test_ports(1)[0]),
                                          'host_ip': '127.0.0.1'}]
        self.admin = ('admin-' + uuid.uuid4().hex[:6], uuid.uuid4().hex + ' $&"')
        services['rabbitmq']['environment'].update(
            {'RABBITMQ_DEFAULT_USER': self.admin[0], 'RABBITMQ_DEFAULT_PASS': self.admin[1]})
        self.passwords = {'orchestrator': uuid.uuid4().hex + ' $&"', 'detector': uuid.uuid4().hex + ' $&"'}
        services['rabbitmq-init']['environment'].update({
            'RABBITMQ_ADMIN_USER': self.admin[0], 'RABBITMQ_ADMIN_PASSWORD': self.admin[1],
            'RABBITMQ_ORCHESTRATOR_PASSWORD': self.passwords['orchestrator'],
            'RABBITMQ_DETECTOR_PASSWORD': self.passwords['detector'],
        })
        self.config = {
            'name': self.project, 'services': services,
            'volumes': {'rabbitmqdata': {'name': self.project + '-rabbitmqdata'}},
            'networks': {'deepfake': {'name': self.project + '-network'}},
        }
        self.write_config()
        self.addCleanup(self.compose, 'down', '--volumes', '--remove-orphans', check=False)
        # Start like an existing deployment: broker with data, before any provisioning.
        self.compose('up', '-d', '--wait', '--wait-timeout', '180', 'rabbitmq')
        self.url = 'http://' + self.compose('port', 'rabbitmq', '15672').stdout.strip()

    def api(self, path, body=None, method=None, user=None, expected=(200,)):
        name, password = user or self.admin
        token = base64.b64encode(f'{name}:{password}'.encode()).decode()
        request = Request(self.url + '/api' + path, method=method,
                          data=None if body is None else json.dumps(body).encode(),
                          headers={'Authorization': 'Basic ' + token, 'Content-Type': 'application/json'})
        try:
            with HTTP.open(request, timeout=10) as response:
                status, payload = response.status, response.read()
        except HTTPError as error:
            with error:
                status, payload = error.code, error.read()
        self.assertIn(status, expected, f'unexpected management status for {path}')
        return json.loads(payload) if payload else None

    def run_init(self):
        return self.compose('run', '--rm', 'rabbitmq-init', check=False)

    def authenticates(self, user, password):
        return self.compose('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'authenticate_user', '--',
                            user, password, check=False).returncode == 0

    def assert_scoped(self, user, allowed, denied):
        permissions = self.api(f'/permissions/%2F/{user}')
        for kind in ('configure', 'write', 'read'):
            pattern = permissions[kind]
            for name in allowed[kind]:
                self.assertTrue(re.fullmatch(pattern, name), f'{user} lacks {kind} on {name}')
            for name in denied[kind]:
                self.assertFalse(re.search(pattern, name), f'{user} has {kind} on {name}')
        topic = self.api(f'/topic-permissions/%2F/{user}')
        self.assertEqual(['analysis.exchange'], [entry['exchange'] for entry in topic])
        for kind in ('write', 'read'):
            for key in allowed['topic_' + kind]:
                self.assertTrue(re.fullmatch(topic[0][kind], key), f'{user} lacks topic {kind} {key}')
            for key in denied['topic_' + kind]:
                self.assertFalse(re.search(topic[0][kind], key), f'{user} has topic {kind} {key}')

    def test_existing_broker_is_migrated_and_users_are_least_privilege(self):
        # Existing durable data must survive provisioning on a pre-existing volume.
        self.api('/queues/%2F/legacy.queue', {'durable': True, 'arguments': {}}, method='PUT',
                 expected=(201, 204))
        result = self.run_init()
        # The script's own messages never contain credential values.
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('RabbitMQ users provisioned: orchestrator, detector', result.stdout)
        logs = result.stdout + result.stderr
        self.assertTrue(all(value not in logs for value in (*self.passwords.values(), self.admin[1])),
                        'rabbitmq-init disclosed a password')
        self.api('/queues/%2F/legacy.queue')
        # Task queues are pre-declared with the application's exact arguments.
        for source in ('video', 'audio'):
            queue = self.api(f'/queues/%2F/analysis.{source}')
            self.assertTrue(queue['durable'])
            self.assertEqual({'x-queue-type': 'quorum', 'x-delivery-limit': 5,
                              'x-dead-letter-exchange': 'analysis.dlx',
                              'x-dead-letter-routing-key': f'analysis.{source}.dlq'}, queue['arguments'])
        self.assertEqual('direct', self.api('/exchanges/%2F/analysis.dlx')['type'])
        self.assertEqual('topic', self.api('/exchanges/%2F/analysis.exchange')['type'])

        users = {user['name']: user['tags'] for user in self.api('/users')}
        self.assertEqual({self.admin[0], 'orchestrator', 'detector'}, set(users))
        self.assertIn('administrator', users[self.admin[0]])
        self.assertFalse(users['orchestrator'])
        self.assertFalse(users['detector'])
        for user, password in self.passwords.items():
            self.assertTrue(self.authenticates(user, password), f'{user} cannot authenticate')
            # Untagged users have no management UI/API access.
            self.api('/whoami', user=(user, password), expected=(401,))
            self.assertFalse(any(p['vhost'] != '/' for p in self.api(f'/users/{user}/permissions')))

        self.assert_scoped('orchestrator', allowed={
            'configure': QUEUES + ('analysis.exchange', 'analysis.dlx'),
            'write': QUEUES + ('analysis.exchange', 'analysis.dlx'),
            'read': ('analysis.results', 'analysis.progress', 'analysis.video.dlq', 'analysis.audio.dlq',
                     'analysis.results.dlq', 'analysis.exchange', 'analysis.dlx'),
            'topic_write': ('analysis.video', 'analysis.audio'),
            'topic_read': ('analysis.video', 'analysis.audio', 'analysis.results', 'analysis.progress'),
        }, denied={
            'configure': ('amq.default', 'legacy.queue', 'other'),
            'write': ('amq.default', 'legacy.queue', 'amq.topic'),
            'read': ('analysis.video', 'analysis.audio', 'analysis.backend.quarantine', 'legacy.queue'),
            'topic_write': ('analysis.results', 'analysis.progress', 'analysis.video.x', '#'),
            'topic_read': ('#', 'analysis.*'),
        })
        self.assert_scoped('detector', allowed={
            'configure': ('analysis.exchange', 'analysis.dlx', 'analysis.video', 'analysis.audio',
                          'analysis.video.dlq', 'analysis.audio.dlq', 'analysis.results', 'analysis.progress'),
            'write': ('analysis.exchange', 'analysis.video', 'analysis.audio', 'analysis.video.dlq',
                      'analysis.audio.dlq', 'analysis.results', 'analysis.progress'),
            'read': ('analysis.exchange', 'analysis.dlx', 'analysis.video', 'analysis.audio'),
            'topic_write': ('analysis.results', 'analysis.progress'),
            'topic_read': ('analysis.video', 'analysis.audio', 'analysis.results', 'analysis.progress'),
        }, denied={
            'configure': ('analysis.results.dlq', 'analysis.backend.quarantine', 'legacy.queue', 'amq.default'),
            'write': ('analysis.dlx', 'analysis.results.dlq', 'analysis.backend.quarantine', 'amq.default'),
            'read': ('analysis.results', 'analysis.progress', 'analysis.video.dlq', 'analysis.audio.dlq',
                     'analysis.results.dlq', 'analysis.backend.quarantine'),
            'topic_write': ('analysis.video', 'analysis.audio', '#'),
            'topic_read': ('#', 'analysis.*'),
        })

        # Re-running is idempotent and applies a rotated password.
        old = self.passwords['detector']
        self.passwords['detector'] = uuid.uuid4().hex
        self.config['services']['rabbitmq-init']['environment']['RABBITMQ_DETECTOR_PASSWORD'] = \
            self.passwords['detector']
        self.write_config()
        self.assertEqual(0, self.run_init().returncode, 'rabbitmq-init is not idempotent')
        self.assertTrue(self.authenticates('detector', self.passwords['detector']))
        self.assertFalse(self.authenticates('detector', old), 'rotated password still accepted')
        self.assertTrue(self.authenticates('orchestrator', self.passwords['orchestrator']))
        self.assertIn('administrator', {u['name']: u['tags'] for u in self.api('/users')}[self.admin[0]])

    def test_invalid_configuration_fails_without_disclosing_or_demoting(self):
        environment = self.config['services']['rabbitmq-init']['environment']
        cases = (
            ({'RABBITMQ_DETECTOR_PASSWORD': ''}, 'RABBITMQ_DETECTOR_PASSWORD must be set and non-empty'),
            ({'RABBITMQ_ORCHESTRATOR_PASSWORD': 'bad\nvalue'},
             'RABBITMQ_ORCHESTRATOR_PASSWORD must not contain control characters'),
            ({'RABBITMQ_ADMIN_USER': 'detector'}, 'RABBITMQ_ADMIN_USER must not be one of'),
            ({'RABBITMQ_ADMIN_PASSWORD': self.admin[1] + 'wrong'},
             'RabbitMQ rejected the administrator credentials'),
        )
        baseline = dict(environment)
        for change, message in cases:
            with self.subTest(case=message):
                environment.clear()
                environment.update({**baseline, **change})
                self.write_config()
                started = time.monotonic()
                result = self.run_init()
                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)
                self.assertLess(time.monotonic() - started, 60, 'invalid configuration was retried')
                logs = result.stdout + result.stderr
                self.assertTrue(all(value not in logs for value in (*self.passwords.values(), self.admin[1])),
                                'rabbitmq-init disclosed a password')
        users = {user['name']: user['tags'] for user in self.api('/users')}
        self.assertEqual({self.admin[0]}, set(users), 'failed provisioning created users')
        self.assertIn('administrator', users[self.admin[0]])


if __name__ == '__main__':
    unittest.main(verbosity=2)
