"""Test the real Compose registry boundary and authenticated lb:// gateway routes.

Run with Python 3.9+ and Docker Compose: python3 infra/tests/test_discovery.py
No ML checkpoints or developer .env files are used. All resources are disposable.
"""

import base64
import ipaddress
import json
import secrets
import socket
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[2]
HTTP = build_opener(ProxyHandler({}))


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True, stderr=subprocess.STDOUT).strip()


def resolved_compose():
    return json.loads(command(
        'docker', 'compose', '--env-file', str(ROOT / '.env.example'),
        '--profile', '*', 'config', '--format', 'json',
    ))


def available_test_ports(count):
    # Docker's published=0 allocator can race with outbound sockets on Linux.
    # Reserve distinct unused ports outside the host's ephemeral source-port range.
    linux_range = Path('/proc/sys/net/ipv4/ip_local_port_range')
    if linux_range.exists():
        first, last = map(int, linux_range.read_text().split())
    else:
        first, last = map(int, command(
            'sysctl', '-n', 'net.inet.ip.portrange.first', 'net.inet.ip.portrange.last',
        ).split())
    reservations = []
    ports = []
    try:
        for _ in range(1000):
            port = 10000 + secrets.randbelow(55536)
            if first <= port <= last:
                continue
            reservation = socket.socket()
            try:
                reservation.bind(('0.0.0.0', port))
            except OSError:
                reservation.close()
                continue
            reservations.append(reservation)
            ports.append(port)
            if len(ports) == count:
                return ports
        raise RuntimeError('Could not reserve unused test ports outside the ephemeral range')
    finally:
        for reservation in reservations:
            reservation.close()


class DiscoveryConfigurationTest(unittest.TestCase):
    def test_registry_is_internal_and_gateway_is_loopback_only(self):
        services = resolved_compose()['services']
        self.assertFalse(services['eureka-server'].get('ports'),
                         'The unauthenticated registry must not publish any host port')
        self.assertEqual(1, len(services['gateway']['ports']))
        port = services['gateway']['ports'][0]
        self.assertEqual('127.0.0.1', port.get('host_ip'))
        self.assertEqual(8080, port['target'])
        for name in ('gateway', 'file-service', 'orchestrator'):
            self.assertEqual('http://eureka-server:8761/eureka/',
                             services[name]['environment'].get('EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE',
                                 'http://eureka-server:8761/eureka/'))
            self.assertIn('deepfake', services[name]['networks'])


class DiscoveryIntegrationTest(unittest.TestCase):
    def compose(self, *args):
        return command('docker', 'compose', '--project-name', self.project,
                       '-f', str(self.compose_file), *args)

    def request(self, base, path, data=None, headers=None):
        with HTTP.open(Request(base + path, data=data, headers=headers or {}), timeout=10) as response:
            return json.load(response)

    def eventually(self, check, timeout=240):
        deadline = time.monotonic() + timeout
        last_error = None
        while time.monotonic() < deadline:
            try:
                return check()
            except (URLError, AssertionError, OSError, subprocess.CalledProcessError) as error:
                last_error = error
                if isinstance(error, HTTPError):
                    error.close()
                time.sleep(2)
        self.fail(f'Discovery did not satisfy the check: {last_error}')

    def setUp(self):
        config = resolved_compose()
        self.assertFalse(config['services']['eureka-server'].get('ports'))
        self.assertEqual('127.0.0.1', config['services']['gateway']['ports'][0].get('host_ip'))
        # Never send a registration attempt to an existing developer registry.
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 8761))
        self.directory = tempfile.TemporaryDirectory(prefix='discovery-integration-')
        self.addCleanup(self.directory.cleanup)
        directory = Path(self.directory.name)
        self.compose_file = directory / 'compose.json'
        self.project = project = 'discovery-test-' + uuid.uuid4().hex[:12]
        names = (
            'postgres', 'redis', 'rabbitmq', 'seaweedfs', 'seaweedfs-init',
            'seaweedfs-bucket-init', 'eureka-server', 'gateway', 'file-service',
            'orchestrator', 'keycloak', 'keycloak-db',
        )
        services = {name: config['services'][name] for name in names}
        gateway_port, identity_port, control_port = available_test_ports(3)
        issuer = 'http://keycloak:8080/realms/discovery-test'
        for name, service in services.items():
            service.pop('profiles', None)
            service['restart'] = 'no'
            if name in ('gateway', 'keycloak'):
                service['ports'][0]['published'] = str(
                    gateway_port if name == 'gateway' else identity_port,
                )
                # Keep the original host_ip, so the runtime test exercises its binding.
            else:
                # Infrastructure clients use Compose DNS; no host ports are needed.
                service.pop('ports', None)
            if name in ('gateway', 'file-service', 'orchestrator'):
                service['environment'].update({
                    'JWT_ISSUER_URI': issuer,
                    'JWK_SET_URI': issuer + '/protocol/openid-connect/certs',
                    'OTEL_TRACING_EXPORT_ENABLED': 'false',
                })
        services['file-service']['deploy']['replicas'] = 1
        realm = directory / 'realm.json'
        realm.write_text(json.dumps({
            'realm': 'discovery-test', 'enabled': True,
            'roles': {'realm': [{'name': 'USER'}]},
            'clients': [{
                'clientId': 'discovery-probe', 'secret': 'disposable-test-secret',
                'enabled': True, 'serviceAccountsEnabled': True,
                'protocol': 'openid-connect', 'publicClient': False,
            }],
            'users': [{
                'username': 'service-account-discovery-probe',
                'enabled': True, 'serviceAccountClientId': 'discovery-probe',
                'realmRoles': ['USER'],
            }],
        }))
        # Only the disposable test realm is needed; no frontend theme build is required.
        services['keycloak']['volumes'] = [{
            'type': 'bind', 'source': str(realm),
            'target': '/opt/keycloak/data/import/realm.json', 'read_only': True,
        }]
        services['keycloak']['command'] = ['start-dev', '--import-realm']
        services['keycloak']['environment']['KC_HOSTNAME'] = 'http://keycloak:8080'
        self.probe_image = services['seaweedfs-init']['image']
        self.outside_network = project + '-outside'
        # Select a real host interface without sending traffic. Docker Desktop's
        # host.docker.internal can forward loopback ports and is not a LAN probe.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(('192.0.2.1', 9))
            self.host_address = probe.getsockname()[0]
        self.assertFalse(ipaddress.ip_address(self.host_address).is_loopback)
        services['probe-control'] = {
            'image': self.probe_image, 'command': ['nc', '-lk', '-p', '8089', '-e', 'cat'],
            'ports': [{'target': 8089, 'published': str(control_port), 'host_ip': '0.0.0.0'}],
            'networks': ['outside'],
        }
        config = {
            'name': project, 'services': services,
            'volumes': {name: {'name': project + '-' + name} for name in (
                'pgdata', 'redisdata', 'rabbitmqdata', 'seaweeddata',
                'seaweedconfig', 'keycloak-pgdata',
            )},
            'networks': {'deepfake': {'name': project + '-network'},
                         'outside': {'name': self.outside_network}},
        }
        self.compose_file.write_text(json.dumps(config))
        # Cleanup addresses only the generated project and its generated volumes/network.
        self.addCleanup(self.compose, 'down', '--volumes', '--remove-orphans')
        self.addCleanup(self.failure_logs)
        print(f'Starting isolated Compose project {project}', flush=True)
        try:
            self.compose('up', '-d', '--build', '--wait', '--wait-timeout', '300')
        except subprocess.CalledProcessError as error:
            print(self.compose('logs', '--tail', '60'), flush=True)
            self.fail(f'Isolated Compose startup failed:\n{error.output}')
        self.refresh_ports()

    def refresh_ports(self):
        self.gateway = 'http://' + self.compose('port', 'gateway', '8080')
        self.identity = 'http://' + self.compose('port', 'keycloak', '8080')

    def failure_logs(self):
        result = self._outcome.result
        if any(test is self for test, _ in result.failures + result.errors):
            print(self.compose('logs', '--tail', '60', 'eureka-server', 'gateway',
                               'file-service', 'orchestrator', 'keycloak'))

    def assert_boundary(self):
        for service in ('eureka-server', 'gateway'):
            state = json.loads(command('docker', 'inspect', self.compose('ps', '-q', service)))[0]
            bindings = state['HostConfig']['PortBindings'] or {}
            if service == 'eureka-server':
                self.assertFalse(bindings, 'Registry unexpectedly published a host port')
            else:
                self.assertEqual({'8080/tcp'}, set(bindings))
                self.assertEqual('127.0.0.1', bindings['8080/tcp'][0]['HostIp'])
        payload = json.dumps({'instance': {
            'instanceId': 'untrusted-probe', 'app': 'UNTRUSTED-PROBE',
            'hostName': 'untrusted.invalid', 'ipAddr': '192.0.2.1', 'status': 'UP',
            'port': {'$': 9999, '@enabled': 'true'},
            'dataCenterInfo': {'@class': 'com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo',
                               'name': 'MyOwn'},
        }}).encode()
        with self.assertRaises(URLError) as blocked:
            self.request('http://127.0.0.1:8761', '/eureka/apps/UNTRUSTED-PROBE',
                         payload, {'Content-Type': 'application/json'})
        self.assertNotIsInstance(blocked.exception, HTTPError,
                                 'Expected a closed socket, not an HTTP response')
        gateway_port = self.gateway.rsplit(':', 1)[1]
        # Confirm transport to the real host interface using a disposable, empty
        # TCP echo server on a wildcard test port before asserting closed ports.
        control_port = self.compose('port', 'probe-control', '8089').rsplit(':', 1)[1]
        def probe_port(port):
            return subprocess.run([
                'docker', 'run', '--rm', '--network', self.outside_network,
                self.probe_image, 'nc', '-z', '-v', '-w', '3', self.host_address, port,
            ], text=True, capture_output=True, check=False)
        control = probe_port(control_port)
        self.assertEqual(0, control.returncode,
                         f'Outside-network host transport failed: {control.stderr}')
        for port in ('8761', gateway_port):
            # A failed HTTP status is insufficient: require no TCP connection.
            result = probe_port(port)
            self.assertEqual(1, result.returncode,
                             f'Host port {port} reachable from the outside network: {result.stderr}')
        print(f'PASS: no registry host binding; host registration refused; '
              f'outside-network TCP probes to {self.host_address} blocked (positive control passed)',
              flush=True)

    def assert_registration_and_routes(self):
        def registered():
            registry = json.loads(self.compose(
                'exec', '-T', 'gateway', 'wget', '-qO-', '--header=Accept: application/json',
                'http://eureka-server:8761/eureka/apps',
            ))
            apps = {app['name']: app for app in registry['applications']['application']}
            for name in ('GATEWAY', 'FILE-SERVICE', 'ORCHESTRATOR'):
                self.assertIn(name, apps)
                self.assertTrue(any(instance['status'] == 'UP' for instance in apps[name]['instance']))
            self.assertNotIn('UNTRUSTED-PROBE', apps)
        self.eventually(registered)
        token = self.eventually(lambda: self.request(
            self.identity, '/realms/discovery-test/protocol/openid-connect/token',
            urlencode({'grant_type': 'client_credentials', 'client_id': 'discovery-probe',
                       'client_secret': 'disposable-test-secret'}).encode(),
            {'Content-Type': 'application/x-www-form-urlencoded'},
        ))['access_token']
        claims = token.split('.')[1]
        owner = json.loads(base64.urlsafe_b64decode(claims + '=' * (-len(claims) % 4)))['sub']
        file_id = str(uuid.uuid4())
        self.compose('exec', '-T', 'postgres', 'psql', '-U', 'deepfake', '-d', 'fileservice',
                     '-v', 'ON_ERROR_STOP=1', '-c',
                     f"INSERT INTO file_metadata (file_id, object_key, user_id, original_name, mimetype, size_bytes) "
                     f"VALUES ('{file_id}', 'discovery-probe', '{owner}', 'discovery-probe.wav', 'audio/wav', 42)")
        def routed():
            headers = {'Authorization': 'Bearer ' + token}
            metadata = self.request(self.gateway, f'/api/files/{file_id}/metadata', headers=headers)
            self.assertEqual(file_id, metadata['fileId'])
            self.assertEqual('discovery-probe.wav', metadata['name'])
            analyses = self.request(self.gateway, '/api/analysis', headers=headers)
            self.assertEqual([], analyses['content'])
            self.assertEqual(0, analyses['page']['totalElements'])
        self.eventually(routed)
        print('PASS: real services registered UP; authenticated gateway lb:// routes reached both databases',
              flush=True)

    def test_registry_isolation_and_discovery_survive_restart(self):
        self.assert_boundary()
        self.assert_registration_and_routes()
        self.compose('restart', 'eureka-server', 'gateway', 'file-service', 'orchestrator')
        self.refresh_ports()
        self.assert_boundary()
        self.assert_registration_and_routes()
        print('LIMITATION: probes ran on one Docker host; no second physical LAN host was tested', flush=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
