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

    def test_storage_has_memory_for_concurrent_uploads(self):
        service = resolved_compose()['services']['seaweedfs']
        self.assertEqual(2 * 1024**3, int(service['deploy']['resources']['limits']['memory']))
        # The Go heap must collect before the cgroup limit instead of pinning the container at it.
        self.assertEqual('1536MiB', service['environment']['GOMEMLIMIT'])

    def test_container_probe_uses_liveness(self):
        services = resolved_compose()['services']
        for name, port in (('orchestrator', 8082), ('gateway', 9090)):
            with self.subTest(service=name):
                self.assertIn(f'http://localhost:{port}/actuator/health/liveness',
                              services[name]['healthcheck']['test'])

    def test_gateway_probe_and_broker_resource_configuration(self):
        services = resolved_compose()['services']
        self.assertIn('http://localhost:9090/actuator/health/liveness',
                      services['gateway'].get('healthcheck', {}).get('test', []))
        probe = services['rabbitmq']['healthcheck']['test']
        self.assertEqual('CMD-SHELL', probe[0])
        self.assertIn('check_running && rabbitmq-diagnostics -q check_port_connectivity', probe[1])
        config = (ROOT / 'infra/rabbitmq/rabbitmq.conf').read_text()
        self.assertIn('total_memory_available_override_value = 512MB', config)
        self.assertIn('raft.wal_max_size_bytes = 67108864', config)

    def test_java_containers_cannot_escalate_or_create_unbounded_processes(self):
        services = resolved_compose()['services']
        for name in JAVA_LIMITS:
            with self.subTest(service=name):
                service = services[name]
                self.assertIn('no-new-privileges:true', service.get('security_opt', []))
                self.assertEqual(['ALL'], service.get('cap_drop'))
                self.assertEqual(300, service.get('pids_limit'))
        for name in ('eureka-server', 'gateway', 'orchestrator'):
            self.assertTrue(services[name].get('read_only'))
            self.assertTrue(any(mount.startswith('/tmp:') for mount in services[name]['tmpfs']))

    def test_postgres_health_requires_the_final_tcp_server(self):
        for name in ('postgres', 'keycloak-db'):
            self.assertIn('pg_isready -h 127.0.0.1 ',
                          resolved_compose()['services'][name]['healthcheck']['test'][1])

    def test_orchestrator_database_credentials_are_not_cluster_admin_credentials(self):
        services = resolved_compose()['services']
        environment = services['orchestrator']['environment']
        self.assertNotIn('POSTGRES_USER', environment)
        self.assertNotIn('POSTGRES_PASSWORD', environment)
        self.assertEqual('orchestrator', environment['ORCHESTRATOR_DB_USER'])
        for name in ('ORCHESTRATOR_DB_USER', 'ORCHESTRATOR_DB_PASSWORD'):
            self.assertEqual(environment[name], services['postgres']['environment'][name])

    def test_gateway_does_not_load_netty_native_transport_from_noexec_tmp(self):
        gateway = resolved_compose()['services']['gateway']
        self.assertTrue(any(mount.startswith('/tmp:') and 'noexec' in mount
                            for mount in gateway['tmpfs']))
        self.assertIn('ENV JAVA_TOOL_OPTIONS="-XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75 '
                      '-Dio.netty.transport.noNative=true"',
                      (ROOT / 'gateway/Dockerfile').read_text())

    def test_redis_reserves_container_headroom_and_only_evicts_expiring_hints(self):
        arguments = resolved_compose()['services']['redis']['command']
        self.assertIn('--maxmemory', arguments)
        self.assertEqual('200mb', arguments[arguments.index('--maxmemory') + 1])
        self.assertEqual('volatile-lru', arguments[arguments.index('--maxmemory-policy') + 1])


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
                service['image'] = cls.project + '-' + name
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
        cls.gateway_url = f'http://127.0.0.1:{gateway_port}'
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

    def test_gateway_actuator_boundary_and_real_prometheus_scrape(self):
        for path in ('/actuator/health', '/actuator/prometheus'):
            self.assertEqual(401, self.request(self.gateway_url, path)[0])
        health = json.loads(self.compose('exec', '-T', 'gateway', 'wget', '-qO-',
                                         'http://localhost:9090/actuator/health'))
        self.assertEqual('UP', health['status'])
        self.assertTrue(set(health) <= {'status', 'groups'}, health)

        def scraped():
            _, result = self.request(self.prometheus_url, '/api/v1/targets')
            targets = [target for target in result['data']['activeTargets']
                       if target['labels']['job'] == 'gateway']
            self.assertEqual(1, len(targets))
            self.assertEqual('http://gateway:9090/actuator/prometheus', targets[0]['scrapeUrl'])
            self.assertEqual('up', targets[0]['health'], targets[0]['lastError'])

        self.eventually(scraped)

    def test_gateway_starts_on_nio_without_native_library_warnings(self):
        logs = self.compose('logs', '--no-color', 'gateway')
        self.assertIn('Netty started on port', logs)
        # Netty's noexec warning and the JDK warning about Netty calling System::loadLibrary.
        for marker in ('libnetty', 'NativeLibraryUtil'):
            self.assertNotIn(marker, logs)

    def test_database_role_migration_preserves_existing_data_and_cluster_ownership(self):
        def sql(database, query, user='deepfake'):
            return self.compose('exec', '-T', 'postgres', 'psql', '-X', '-At',
                                '-v', 'ON_ERROR_STOP=1', '-U', user, '-d', database, '-c', query)

        self.assertEqual('f|f|f|f|f', sql('deepfake',
            "SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls "
            "FROM pg_roles WHERE rolname = 'orchestrator'"))
        self.assertEqual('orchestrator', sql('deepfake',
            "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()"))
        denied = self.compose('exec', '-T', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                              '-U', 'orchestrator', '-d', 'fileservice', '-c',
                              'SELECT * FROM file_metadata', check=False)
        self.assertIn('permission denied', denied)
        sql('postgres', 'CREATE DATABASE migration_probe')
        sql('migration_probe', "CREATE TABLE preserved (id integer PRIMARY KEY, value text); "
            "INSERT INTO preserved VALUES (1, 'keep me'); CREATE SEQUENCE preserved_seq; "
            "CREATE VIEW preserved_view AS SELECT * FROM preserved")
        before = sql('postgres', "SELECT datname, pg_get_userbyid(datdba) FROM pg_database "
                     "WHERE datname <> 'migration_probe' ORDER BY datname")
        script = ROOT / 'infra/postgres/init/02-init-orchestrator-db.sh'
        self.compose('cp', str(script), 'postgres:/tmp/migrate-role.sh')
        for _ in range(2):
            self.compose('exec', '-T', '-e', 'POSTGRES_DB=migration_probe',
                         '-e', 'ORCHESTRATOR_DB_USER=migration_probe_user',
                         '-e', "ORCHESTRATOR_DB_PASSWORD=disposable'quoted-password",
                         'postgres', 'bash', '/tmp/migrate-role.sh')
        authenticated = self.compose('exec', '-T',
            '-e', "PGPASSWORD=disposable'quoted-password", 'postgres', 'psql', '-X', '-At',
            '-h', '127.0.0.1', '-U', 'migration_probe_user', '-d', 'migration_probe',
            '-c', 'SELECT current_user')
        self.assertEqual('migration_probe_user', authenticated)
        rejected = self.compose('exec', '-T', '-e', 'ORCHESTRATOR_DB_USER=deepfake',
                                'postgres', 'bash', '/tmp/migrate-role.sh', check=False)
        self.assertIn('Refusing overlapping', rejected)
        self.assertEqual('1|keep me', sql('migration_probe', 'SELECT * FROM preserved',
                                        'migration_probe_user'))
        self.assertEqual('1|keep me', sql('migration_probe', 'SELECT * FROM preserved_view',
                                        'migration_probe_user'))
        sql('migration_probe', "ALTER TABLE preserved ADD COLUMN migrated boolean; "
            "SELECT nextval('preserved_seq'); CREATE TABLE flyway_probe (id int)",
            'migration_probe_user')
        self.assertEqual(before, sql('postgres',
            "SELECT datname, pg_get_userbyid(datdba) FROM pg_database "
            "WHERE datname <> 'migration_probe' ORDER BY datname"))

    def test_fresh_stack_dashboard_counters_have_data_before_any_analysis(self):
        # Percentiles and ratios have no value without observations; detectors are not in core.
        dashboard = json.loads((ROOT / 'infra/grafana/dashboards/deepfake-business.json').read_text())
        expressions = [target['expr'] for panel in dashboard['panels']
                       for target in panel.get('targets', [])
                       if not any(skip in target['expr'] for skip in (
                           'histogram_quantile', ' / ', 'video-detector', 'audio-detector'))]
        for metric in ('analyses_total', 'analyses_deleted_total', 'analyses_inflight',
                       'gateway_ratelimiter_degraded_total', 'rabbitmq_queue_messages_ready'):
            self.assertTrue(any(metric in expression for expression in expressions), metric)

        def every_panel_has_data():
            for expression in expressions:
                status, response = self.request(
                    self.prometheus_url, '/api/v1/query?query=' + quote(expression, safe=''))
                self.assertEqual(200, status)
                self.assertTrue(response['data']['result'], expression)

        self.eventually(every_panel_has_data, timeout=180)

    def test_gateway_stays_healthy_during_redis_outage(self):
        original = self.containers('gateway')[0]
        self.compose('stop', 'redis')
        try:
            failed_at = datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')

            def diagnostic_health_down():
                response = self.compose('exec', '-T', 'gateway', 'wget', '-S', '-O', '-',
                                        'http://localhost:9090/actuator/health', check=False)
                self.assertIn('503', response)

            self.eventually(diagnostic_health_down)
            probe = original['Config']['Healthcheck']['Test']
            self.assertEqual('CMD', probe[0])
            command('docker', 'exec', original['Id'], *probe[1:])

            def live_after_automatic_probe():
                diagnostic_health_down()
                health = json.loads(self.compose('exec', '-T', 'gateway', 'wget', '-qO-',
                                                 'http://localhost:9090/actuator/health/liveness'))
                self.assertEqual('UP', health['status'])
                current = self.containers('gateway')[0]
                self.assertEqual(original['State']['StartedAt'], current['State']['StartedAt'])
                self.assertEqual(original['RestartCount'], current['RestartCount'])
                health = current['State']['Health']
                self.assertGreater(health['Log'][-1]['End'], failed_at)
                self.assertEqual(0, health['Log'][-1]['ExitCode'])
                self.assertEqual('healthy', health['Status'])

            self.eventually(live_after_automatic_probe, timeout=45)
        finally:
            self.compose('start', 'redis')

        self.eventually(lambda: self.assertEqual('UP', json.loads(self.compose(
            'exec', '-T', 'gateway', 'wget', '-qO-',
            'http://localhost:9090/actuator/health',
        ))['status']))

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

    def test_java_heap_uses_container_budget_with_native_headroom(self):
        for name, limit in JAVA_LIMITS.items():
            for container in self.containers(name):
                with self.subTest(service=name, container=container['Name']):
                    output = command('docker', 'exec', container['Id'],
                                     'java', '-XX:+PrintFlagsFinal', '-version')
                    flags = {}
                    for line in output.splitlines():
                        fields = line.split()
                        if len(fields) >= 4 and fields[1] in (
                            'MaxRAMPercentage', 'MaxHeapSize',
                            'UseCompactObjectHeaders', 'UseContainerSupport',
                        ):
                            flags[fields[1]] = fields[3]
                    self.assertEqual('true', flags['UseContainerSupport'])
                    self.assertEqual('true', flags['UseCompactObjectHeaders'])
                    self.assertEqual(75.0, float(flags['MaxRAMPercentage']))
                    heap = int(flags['MaxHeapSize'])
                    self.assertGreaterEqual(heap, limit * 0.70)
                    self.assertLessEqual(heap, limit * 0.75)
                    print(f'{name}: max heap {heap // 1024**2} MiB / '
                          f'container {limit // 1024**2} MiB', flush=True)

    def test_broker_and_redis_apply_the_configured_memory_budgets(self):
        memory = self.compose('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'eval',
                              'vm_memory_monitor:get_total_memory().')
        self.assertEqual(512_000_000, int(memory))
        wal = self.compose('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'eval',
                           'application:get_env(ra, wal_max_size_bytes).')
        self.assertEqual('{ok,67108864}', wal)
        for setting, expected in (('maxmemory', '209715200'),
                                  ('maxmemory-policy', 'volatile-lru')):
            values = self.compose(
                'exec', '-T', 'redis', 'redis-cli', '-a',
                resolved_compose()['services']['redis']['command'][2],
                '--no-auth-warning', '--raw', 'CONFIG', 'GET', setting,
            )
            self.assertEqual([setting, expected], values.splitlines())

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
        original_ids = {container['Id'] for container in self.containers('file-service')}
        config = json.loads(self.compose_file.read_text())
        try:
            config['services']['file-service']['deploy']['replicas'] = 1
            self.compose_file.write_text(json.dumps(config))
            self.compose('up', '-d', '--no-deps', '--no-recreate', '--scale', 'file-service=1', 'file-service')
            self.eventually(lambda: assert_targets(1))
            retained_ids = {container['Id'] for container in self.containers('file-service')}
            self.assertTrue(retained_ids <= original_ids)
        finally:
            config['services']['file-service']['deploy']['replicas'] = 2
            self.compose_file.write_text(json.dumps(config))
            self.compose('up', '-d', '--no-deps', '--no-recreate', '--scale', 'file-service=2', 'file-service')
        self.eventually(lambda: assert_targets(2))
        self.assertTrue(retained_ids <= {container['Id'] for container in self.containers('file-service')})

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
                self.assertEqual('DOWN', body['status'])
                self.assertTrue(set(body) <= {'status', 'groups'}, body)

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
