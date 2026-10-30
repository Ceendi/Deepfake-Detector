"""Validate monitoring configs and boot hardened services on disposable volumes."""

import json
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request

from test_discovery import HTTP, ROOT, available_test_ports, command, resolved_compose

MEMORY_MIB = {'prometheus': 512, 'loki': 512, 'tempo': 512, 'grafana': 256, 'alloy': 256}


class MonitoringConfigurationTest(unittest.TestCase):
    def test_monitoring_is_bounded_and_hardened(self):
        services = resolved_compose()['services']
        for name, memory in MEMORY_MIB.items():
            with self.subTest(service=name):
                service = services[name]
                self.assertEqual(memory * 1024**2,
                                 int(service['deploy']['resources']['limits']['memory']))
                self.assertEqual(300, service.get('pids_limit'))
                self.assertIn('no-new-privileges:true', service.get('security_opt', []))
                self.assertEqual(['ALL'], service.get('cap_drop'))
                self.assertTrue(service.get('read_only'))
        self.assertNotIn('--web.enable-lifecycle', services['prometheus']['command'])

    def test_alloy_discovery_is_scoped_to_the_resolved_compose_project(self):
        config = resolved_compose()
        self.assertEqual(config['name'],
                         config['services']['alloy']['environment']['ALLOY_COMPOSE_PROJECT'])
        alloy = (ROOT / 'infra/alloy/config.alloy').read_text()
        self.assertIn('name = "label"', alloy)
        self.assertIn('"com.docker.compose.project=" + sys.env("ALLOY_COMPOSE_PROJECT")', alloy)


class MonitoringValidationTest(unittest.TestCase):
    def test_native_validators_accept_config_and_reject_broken_input(self):
        services = resolved_compose()['services']
        command('docker', 'compose', '--env-file', str(ROOT / '.env.example'),
                '--profile', 'monitoring', 'build', 'alloy')
        for name, entrypoint, args, relative in (
            ('prometheus', '/bin/promtool', ('check', 'config'), 'prometheus/prometheus.yml'),
            ('loki', '/usr/bin/loki', ('-verify-config=true', '-config.file='), 'loki/loki-config.yaml'),
            ('alloy', '/bin/alloy', ('validate',), 'alloy/config.alloy'),
        ):
            with self.subTest(service=name), tempfile.TemporaryDirectory() as directory:
                valid = ROOT / 'infra' / relative
                broken = Path(directory) / valid.name
                broken.write_text('invalid_config = [\n')
                for source, expected in ((valid, 0), (broken, 1)):
                    config_path = '/config/' + valid.name
                    arguments = [*args]
                    if arguments[-1].endswith('='):
                        arguments[-1] += config_path
                    else:
                        arguments.append(config_path)
                    rule_mount = ([
                        '--mount', 'type=bind,src=' + str(ROOT / 'infra/prometheus/alerts.yml')
                        + ',dst=/etc/prometheus/alerts.yml,readonly',
                    ] if name == 'prometheus' else [])
                    result = subprocess.run([
                        'docker', 'run', '--rm', '--network', 'none',
                        '--security-opt', 'no-new-privileges:true', '--cap-drop', 'ALL',
                        '--memory', str(MEMORY_MIB[name]) + 'm', '--pids-limit', '300',
                        '--env', 'ALLOY_COMPOSE_PROJECT=monitoring-validation',
                        '--mount', f'type=bind,src={source},dst={config_path},readonly',
                        *rule_mount,
                        '--entrypoint', entrypoint, services[name]['image'], *arguments,
                    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                    if expected == 0:
                        self.assertEqual(0, result.returncode, result.stdout)
                    else:
                        self.assertNotEqual(0, result.returncode, result.stdout)
        for dashboard in (ROOT / 'infra/grafana/dashboards').glob('*.json'):
            command('python3', '-m', 'json.tool', str(dashboard))


class MonitoringIntegrationTest(unittest.TestCase):
    def test_detector_dns_targets_follow_replica_scaling(self):
        services = resolved_compose()['services']
        project = 'detector-scrape-' + uuid.uuid4().hex[:12]
        port = available_test_ports(1)[0]
        prometheus = services['prometheus']
        prometheus.pop('profiles', None)
        prometheus['ports'][0]['published'] = str(port)
        # Serve real HTTP metrics without loading ML models or consuming worker queues.
        # Concurrent like uvicorn: a timed-out scrape of a removed replica keeps retrying its SYN
        # and can connect to the next replica that reuses the IP, leaving an idle socket that
        # would block a serial server forever.
        server = (
            'from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\n'
            'class Metrics(BaseHTTPRequestHandler):\n'
            ' def do_GET(self):\n'
            '  self.send_response(200)\n'
            '  self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")\n'
            '  self.end_headers(); self.wfile.write(b"fixture_up 1\\n")\n'
            'ThreadingHTTPServer(("0.0.0.0", 8000), Metrics).serve_forever()\n'
        )
        detectors = {name: {
            'image': services['rabbitmq-init']['image'],
            'command': ['python', '-c', server],
            'networks': ['deepfake'], 'read_only': True,
            'mem_limit': '64m', 'pids_limit': 50,
            'cap_drop': ['ALL'], 'security_opt': ['no-new-privileges:true'],
        } for name in ('video-detector', 'audio-detector')}
        config = {
            'services': {'prometheus': prometheus, **detectors},
            'volumes': {'prometheus-data': {'name': project + '-prometheus-data'}},
            'networks': {'deepfake': {'name': project + '-network'}},
        }
        with tempfile.TemporaryDirectory() as directory:
            compose_file = Path(directory) / 'compose.json'
            compose_file.write_text(json.dumps(config))

            def compose(*args):
                return command('docker', 'compose', '-p', project, '-f', str(compose_file), *args)

            def targets_match(replicas):
                for name in detectors:
                    containers = json.loads(command('docker', 'inspect', *compose('ps', '-q', name).split()))
                    self.assertEqual(replicas, len(containers))
                    expected = {
                        next(iter(item['NetworkSettings']['Networks'].values()))['IPAddress'] + ':8000'
                        for item in containers
                    }
                    with HTTP.open(f'http://127.0.0.1:{port}/api/v1/targets', timeout=5) as response:
                        targets = [target for target in json.load(response)['data']['activeTargets']
                                   if target['labels']['job'] == name]
                    self.assertEqual(replicas, len(targets))
                    self.assertEqual(expected, {target['labels']['instance'] for target in targets})
                    self.assertTrue(all(target['health'] == 'up' for target in targets), targets)
                    self.assertEqual({'http://' + address + '/metrics' for address in expected},
                                     {target['scrapeUrl'] for target in targets})

            try:
                for replicas in (2, 1, 2):
                    compose('up', '-d', '--wait', '--wait-timeout', '90',
                            '--scale', 'video-detector=' + str(replicas),
                            '--scale', 'audio-detector=' + str(replicas))
                    self.eventually(lambda: targets_match(replicas))
            finally:
                compose('down', '--volumes', '--remove-orphans')

    def test_hardened_stack_starts_and_only_collects_its_own_project(self):
        config = resolved_compose()
        command('docker', 'compose', '--env-file', str(ROOT / '.env.example'),
                '--profile', 'monitoring', 'build', 'alloy')
        project = 'monitoring-test-' + uuid.uuid4().hex[:12]
        services = {name: config['services'][name] for name in MEMORY_MIB}
        prometheus_port, loki_port, tempo_port, grafana_port, alloy_port = available_test_ports(5)
        ports = dict(zip(MEMORY_MIB, (prometheus_port, loki_port, tempo_port, grafana_port, alloy_port)))
        for name, service in services.items():
            service.pop('profiles', None)
            service['ports'][0]['published'] = str(ports[name])
        services['alloy']['environment']['ALLOY_COMPOSE_PROJECT'] = project
        services['grafana']['environment']['GF_SECURITY_ADMIN_PASSWORD'] = uuid.uuid4().hex
        volume_names = ('prometheus-data', 'loki-data', 'tempo-data', 'alloy-data', 'grafana-data')
        config = {
            'services': services,
            'volumes': {name: {'name': project + '-' + name} for name in volume_names},
            'networks': {'deepfake': {'name': project + '-network'}},
        }
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = temporary.name
        compose_file = Path(directory) / 'compose.json'
        compose_file.write_text(json.dumps(config))

        def compose(*args):
            return command('docker', 'compose', '--project-name', project,
                           '-f', str(compose_file), *args)

        self.addCleanup(compose, 'down', '--volumes', '--remove-orphans')
        try:
            compose('up', '-d', '--wait', '--wait-timeout', '180')

            def ready():
                for name, path in (('prometheus', '/-/ready'), ('loki', '/ready'),
                                   ('tempo', '/ready'), ('grafana', '/api/health'),
                                   ('alloy', '/-/ready')):
                    with HTTP.open(f'http://127.0.0.1:{ports[name]}{path}', timeout=5) as response:
                        self.assertEqual(200, response.status, name)

            self.eventually(ready)
            for name, memory in MEMORY_MIB.items():
                container = json.loads(command('docker', 'inspect', compose('ps', '-q', name)))[0]
                host = container['HostConfig']
                self.assertEqual(memory * 1024**2, host['Memory'])
                self.assertEqual(300, host['PidsLimit'])
                self.assertTrue(host['ReadonlyRootfs'])
                self.assertEqual(['ALL'], host['CapDrop'])

            with HTTP.open(Request(f'http://127.0.0.1:{prometheus_port}/-/reload',
                                   method='POST'), timeout=5) as response:
                self.fail(f'Reload should be disabled, got {response.status}')
        except HTTPError as error:
            try:
                self.assertEqual(403, error.code)
            finally:
                error.close()
        except Exception:
            print(compose('logs', '--tail', '30'), flush=True)
            raise

        for label, marker in ((project, 'included-' + project),
                              ('foreign-' + project, 'excluded-' + project)):
            container = command('docker', 'run', '-d', '--rm', '--network', 'none',
                                '--label', 'com.docker.compose.project=' + label,
                                '--label', 'com.docker.compose.service=log-fixture',
                                '--entrypoint', 'sh', config['services']['prometheus']['image'],
                                '-c', 'echo "$1"; sleep 180', 'sh', marker)
            self.addCleanup(subprocess.run, ['docker', 'rm', '-f', container],
                            capture_output=True, check=False)

        def collected():
            query = urlencode({'query': '{service="log-fixture"}'})
            with HTTP.open(f'http://127.0.0.1:{loki_port}/loki/api/v1/query_range?{query}',
                           timeout=10) as response:
                result = json.load(response)
            lines = [line for stream in result['data']['result'] for _, line in stream['values']]
            self.assertIn('included-' + project, lines)
            self.assertNotIn('excluded-' + project, lines)

        self.eventually(collected)
        # Observe beyond Docker discovery's 60 s refresh so delayed foreign logs cannot pass.
        deadline = time.monotonic() + 65
        while time.monotonic() < deadline:
            collected()
            time.sleep(2)

    def eventually(self, check):
        deadline = time.monotonic() + 120
        last_error = None
        while time.monotonic() < deadline:
            try:
                return check()
            except (URLError, AssertionError, OSError) as error:
                last_error = error
                if isinstance(error, HTTPError):
                    error.close()
                time.sleep(2)
        self.fail(str(last_error))


if __name__ == '__main__':
    unittest.main(verbosity=2)
