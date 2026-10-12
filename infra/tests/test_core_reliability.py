"""Verify core startup, process recovery, broker persistence and replica scraping.

Run with Python 3.9+ and Docker Compose: python3 infra/tests/test_core_reliability.py
Only disposable projects, volumes and loopback ports are used; no developer .env.
"""

import base64
import json
import subprocess
import tempfile
import time
import unittest
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request

from test_discovery import HTTP, ROOT, available_test_ports, command, resolved_compose

JAVA_LIMITS = {
    'eureka-server': 512 * 1024**2,
    'gateway': 512 * 1024**2,
    'file-service': 1024**3,
    'orchestrator': 1024**3,
}


class CoreReliabilityConfigurationTest(unittest.TestCase):
    def test_java_process_recovery_and_resource_bounds(self):
        services = resolved_compose()['services']
        for name, limit in JAVA_LIMITS.items():
            service = services[name]
            expected = {
                'restart': 'unless-stopped', 'mem_limit': limit,
                'logging': {
                    'driver': 'json-file',
                    'options': {'max-size': '10m', 'max-file': '3'},
                },
            }
            for field, value in expected.items():
                with self.subTest(service=name, field=field):
                    actual = service.get(field)
                    if field == 'mem_limit':
                        actual = int(actual or 0)
                    self.assertEqual(value, actual)
        self.assertEqual(JAVA_LIMITS['file-service'],
                         int(services['file-service']['deploy']['resources']['limits']['memory']))

    def test_container_probe_uses_liveness(self):
        service = resolved_compose()['services']['orchestrator']
        self.assertIn('http://localhost:8082/actuator/health/liveness',
                      service['healthcheck']['test'])


class CoreReliabilityIntegrationTest(unittest.TestCase):
    @classmethod
    def compose(cls, *args, check=True):
        result = subprocess.run([
            'docker', 'compose', '--project-name', cls.project,
            '-f', str(cls.compose_file), '--profile', 'core', *args,
        ], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            check=check)
        return result.stdout.strip()

    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix='core-reliability-')
        cls.addClassCleanup(cls.directory.cleanup)
        cls.project = 'core-reliability-' + uuid.uuid4().hex[:12]
        cls.compose_file = Path(cls.directory.name) / 'compose.json'
        config = resolved_compose()
        services = {
            name: service for name, service in config['services'].items()
            if 'core' in service.get('profiles', [])
        }
        services['prometheus'] = config['services']['prometheus']
        services['prometheus'].pop('profiles', None)
        gateway_port, broker_port, prometheus_port, orchestrator_port = available_test_ports(4)
        exposed = {'gateway': (8080, gateway_port), 'rabbitmq': (15672, broker_port),
                   'prometheus': (9090, prometheus_port),
                   'orchestrator': (8082, orchestrator_port)}
        for name, service in services.items():
            service.pop('ports', None)
            if name in exposed:
                target, published = exposed[name]
                service['ports'] = [{'target': target, 'published': str(published),
                                     'host_ip': '127.0.0.1'}]
            if name in JAVA_LIMITS:
                service['environment']['OTEL_TRACING_EXPORT_ENABLED'] = 'false'
        services['file-service']['deploy']['replicas'] = 2
        config = {
            'name': cls.project, 'services': services,
            'volumes': {name: {'name': cls.project + '-' + name} for name in (
                'pgdata', 'redisdata', 'rabbitmqdata', 'seaweeddata',
                'seaweedconfig', 'prometheus-data',
            )},
            'networks': {'deepfake': {'name': cls.project + '-network'}},
        }
        cls.compose_file.write_text(json.dumps(config))
        cls.addClassCleanup(cls.compose, 'down', '--volumes', '--remove-orphans')
        print(f'Starting fresh core volumes in {cls.project}', flush=True)
        try:
            cls.compose('up', '-d', '--build', '--wait', '--wait-timeout', '300')
        except subprocess.CalledProcessError as error:
            print(cls.compose('logs', '--tail', '50'), flush=True)
            raise RuntimeError(f'Fresh core startup failed:\n{error.stdout}') from error
        cls.broker_url = f'http://127.0.0.1:{broker_port}'
        cls.prometheus_url = f'http://127.0.0.1:{prometheus_port}'
        cls.orchestrator_url = f'http://127.0.0.1:{orchestrator_port}'
        environment = services['rabbitmq']['environment']
        cls.broker_authorization = 'Basic ' + base64.b64encode(
            (environment['RABBITMQ_DEFAULT_USER'] + ':'
             + environment['RABBITMQ_DEFAULT_PASS']).encode(),
        ).decode()

    def tearDown(self):
        result = self._outcome.result
        if any(test is self for test, _ in result.failures + result.errors):
            print(self.compose('logs', '--tail', '50', 'orchestrator', 'rabbitmq',
                               'file-service', 'prometheus'), flush=True)

    def request(self, base, path, payload=None, method=None, headers=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = Request(base + path, data=data, method=method,
                          headers={'Content-Type': 'application/json', **(headers or {})})
        try:
            response = HTTP.open(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            body = response.read()
            return response.status, json.loads(body) if body else None

    def broker(self, path, payload=None, method=None, expected=200):
        status, body = self.request(self.broker_url, '/api' + path, payload, method,
                                    {'Authorization': self.broker_authorization})
        self.assertEqual(expected, status, f'RabbitMQ API {path}: {body}')
        return body

    def eventually(self, check, timeout=120):
        deadline = time.monotonic() + timeout
        last_error = None
        while time.monotonic() < deadline:
            try:
                return check()
            except (URLError, AssertionError, OSError, subprocess.CalledProcessError) as error:
                last_error = error
                if isinstance(error, HTTPError):
                    error.close()
                time.sleep(1)
        self.fail(f'Core reliability check timed out: {last_error}')

    def containers(self, service):
        ids = self.compose('ps', '-q', service).splitlines()
        self.assertTrue(ids, f'No running containers for {service}')
        return json.loads(command('docker', 'inspect', *ids))

    def test_java_process_exits_restart_with_bounded_memory_and_logs(self):
        originals = {}
        for name, limit in JAVA_LIMITS.items():
            for container in self.containers(name):
                originals[container['Id']] = container['State']['StartedAt']
                host = container['HostConfig']
                self.assertEqual('unless-stopped', host['RestartPolicy']['Name'])
                self.assertEqual(limit, host['Memory'])
                self.assertEqual({'Type': 'json-file',
                                  'Config': {'max-size': '10m', 'max-file': '3'}},
                                 host['LogConfig'])
                # A signal inside the container does not disable Docker's restart policy.
                subprocess.run(['docker', 'exec', container['Id'], 'sh', '-c', 'kill -TERM 1'],
                               capture_output=True, check=False)

        def restarted():
            for name in JAVA_LIMITS:
                for container in self.containers(name):
                    self.assertIn(container['Id'], originals)
                    self.assertNotEqual(originals[container['Id']], container['State']['StartedAt'])
                    self.assertGreaterEqual(container['RestartCount'], 1)
                    self.assertTrue(container['State']['Running'])
                    if 'Health' in container['State']:
                        self.assertEqual('healthy', container['State']['Health']['Status'])
            status, body = self.request(self.orchestrator_url, '/actuator/health/readiness')
            self.assertEqual(200, status)
            self.assertEqual('UP', body['status'])

        self.eventually(restarted, timeout=180)

    def test_rabbitmq_recreation_preserves_durable_message_and_node(self):
        queue = 'test.infra.persistence'
        self.broker('/queues/%2F/' + queue, {'durable': True, 'auto_delete': False,
                                           'arguments': {}}, method='PUT', expected=201)
        self.broker('/exchanges/%2F/amq.default/publish', {
            'properties': {'delivery_mode': 2}, 'routing_key': queue,
            'payload': 'persistent-before-recreate', 'payload_encoding': 'string',
        })
        node = self.broker('/nodes')[0]['name']
        original_id = self.containers('rabbitmq')[0]['Id']
        self.compose('up', '-d', '--no-deps', '--force-recreate', '--wait',
                     '--wait-timeout', '120', 'rabbitmq')
        self.assertNotEqual(original_id, self.containers('rabbitmq')[0]['Id'])
        self.assertEqual(node, self.broker('/nodes')[0]['name'])
        messages = self.broker('/queues/%2F/' + queue + '/get', {
            'count': 1, 'ackmode': 'ack_requeue_false', 'encoding': 'auto',
        })
        self.assertEqual(['persistent-before-recreate'], [item['payload'] for item in messages])
        self.broker('/queues/%2F/' + queue, method='DELETE', expected=204)

    def test_prometheus_scrapes_every_replica_and_refreshes_after_scaling(self):
        def assert_targets(replicas):
            containers = self.containers('file-service')
            self.assertEqual(replicas, len(containers))
            expected = {
                'http://' + next(iter(container['NetworkSettings']['Networks'].values()))['IPAddress']
                + ':8081/actuator/prometheus' for container in containers
            }
            status, response = self.request(self.prometheus_url, '/api/v1/targets')
            self.assertEqual(200, status)
            targets = [target for target in response['data']['activeTargets']
                       if target['labels']['job'] == 'file-service']
            self.assertEqual(expected, {target['scrapeUrl'] for target in targets})
            self.assertTrue(all(target['health'] == 'up' for target in targets), targets)
            status, response = self.request(self.prometheus_url,
                                            '/api/v1/query?query=up%7Bjob%3D%22file-service%22%7D')
            self.assertEqual(200, status)
            samples = response['data']['result']
            self.assertEqual(replicas, len(samples))
            self.assertTrue(all(sample['value'][1] == '1' for sample in samples))

        self.eventually(lambda: assert_targets(2))
        try:
            self.compose('up', '-d', '--no-deps', '--scale', 'file-service=1', 'file-service')
            self.eventually(lambda: assert_targets(1))
        finally:
            self.compose('up', '-d', '--no-deps', '--scale', 'file-service=2', 'file-service')
        self.eventually(lambda: assert_targets(2))

    def test_amqp_recovery_down_keeps_container_liveness_healthy(self):
        bindings_path = '/bindings/%2F/e/analysis.dlx/q/analysis.backend.quarantine'
        bindings = self.broker(bindings_path)
        self.assertEqual(1, len(bindings))
        binding = bindings[0]
        self.broker(bindings_path + '/' + quote(binding['properties_key'], safe=''),
                    method='DELETE', expected=204)
        try:
            self.broker('/exchanges/%2F/analysis.dlx/publish', {
                'properties': {'delivery_mode': 2, 'content_type': 'application/json'},
                'routing_key': 'analysis.video.dlq',
                'payload': json.dumps({'analysis_id': 'poison'}), 'payload_encoding': 'string',
            })

            def readiness_down():
                status, body = self.request(self.orchestrator_url, '/actuator/health/readiness')
                self.assertEqual(503, status)
                self.assertEqual('DOWN', body['components']['amqpRecovery']['status'])

            self.eventually(readiness_down)
            failed_at = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')
            container = self.containers('orchestrator')[0]
            started_at = container['State']['StartedAt']
            probe = container['Config']['Healthcheck']['Test']
            self.assertEqual('CMD', probe[0])
            result = subprocess.run(['docker', 'exec', container['Id'], *probe[1:]],
                                    text=True, capture_output=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)

            def live_after_automatic_probe():
                readiness_down()
                self.assertEqual(200, self.request(self.orchestrator_url,
                                                  '/actuator/health/liveness')[0])
                current = self.containers('orchestrator')[0]
                self.assertEqual(started_at, current['State']['StartedAt'])
                health = current['State']['Health']
                self.assertTrue(health['Log'])
                self.assertGreater(health['Log'][-1]['End'], failed_at)
                self.assertEqual(0, health['Log'][-1]['ExitCode'])
                self.assertEqual('healthy', health['Status'])

            self.eventually(live_after_automatic_probe, timeout=45)
        finally:
            self.broker(bindings_path, {'routing_key': binding['routing_key'],
                                        'arguments': binding['arguments']},
                        expected=201)
        self.eventually(lambda: self.assertEqual(
            200, self.request(self.orchestrator_url, '/actuator/health/readiness')[0],
        ))


if __name__ == '__main__':
    unittest.main(verbosity=2)
