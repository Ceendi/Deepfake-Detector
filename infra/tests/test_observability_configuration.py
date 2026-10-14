"""Validate operator overrides, provisioned monitoring and Prometheus rule behavior."""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_discovery import ROOT, command, resolved_compose


class ObservabilityConfigurationTest(unittest.TestCase):
    def test_core_defaults_do_not_require_monitoring_or_extra_replicas(self):
        config = resolved_compose()['services']
        self.assertEqual('false', config['orchestrator']['environment']['OTEL_TRACING_EXPORT_ENABLED'])
        self.assertEqual(1, config['file-service']['deploy']['replicas'])

    def test_orchestrator_operator_overrides_reach_the_container(self):
        overrides = {
            'RELIABILITY_STUCK_JOB_THRESHOLD_SECONDS': '601',
            'RELIABILITY_STUCK_JOB_SCAN_INTERVAL_MS': '300001',
            'RELIABILITY_STUCK_JOB_INITIAL_DELAY': '601s',
            'RELIABILITY_STUCK_JOB_QUEUE_TIMEOUT_SECONDS': '1801',
            'RELIABILITY_STUCK_JOB_MAX_AGE_SECONDS': '7201',
            'RELIABILITY_IDEMPOTENCY_TTL_SECONDS': '901',
            'FILE_SERVICE_BASE_URL': 'http://override:8081',
            'FILE_SERVICE_LOOKUP_TIMEOUT': '3s',
            'BACKPRESSURE_RETRY_AFTER_SECONDS': '6',
        }
        output = subprocess.check_output(
            ['docker', 'compose', '--env-file', str(ROOT / '.env.example'),
             '--profile', '*', 'config', '--format', 'json'],
            cwd=ROOT, env={**os.environ, **overrides}, text=True,
        )
        environment = json.loads(output)['services']['orchestrator']['environment']
        for name, value in overrides.items():
            with self.subTest(variable=name):
                self.assertEqual(value, environment.get(name))

    def test_dashboard_percentiles_and_detector_outcomes_are_provisioned(self):
        dashboard = json.loads((ROOT / 'infra/grafana/dashboards/deepfake-business.json').read_text())
        panels = {panel['id']: panel for panel in dashboard['panels']}
        for panel_id, metric in ((4, 'analysis_duration_seconds_bucket'),
                                 (5, 'http_server_requests_seconds_bucket')):
            expression = panels[panel_id]['targets'][0]['expr']
            self.assertIn('histogram_quantile(0.95', expression)
            self.assertIn(metric, expression)
        expressions = '\n'.join(target['expr'] for panel in panels.values()
                                for target in panel.get('targets', []))
        for metric in ('inference_latency_seconds_bucket', 'audio_inference_duration_seconds_bucket',
                       'detector_results_total', 'audio_processed_total'):
            self.assertIn(metric, expressions)
        self.assertIn('row', {panel['type'] for panel in panels.values()})

    def test_native_prometheus_validation_and_alert_scenarios(self):
        service = resolved_compose()['services']['prometheus']
        mounts = [value for volume in service['volumes'] if volume['type'] == 'bind'
                  for value in ('--volume', volume['source'] + ':' + volume['target'] + ':ro')]
        fixture = ROOT / 'infra/tests/fixtures/observability-alerts.yml'
        command('docker', 'run', '--rm', '--network', 'none', *mounts,
                '--volume', str(fixture) + ':/tests/alerts.yml:ro',
                '--entrypoint', '/bin/promtool', service['image'],
                'check', 'config', '/etc/prometheus/prometheus.yml')
        command('docker', 'run', '--rm', '--network', 'none', *mounts,
                '--volume', str(fixture) + ':/tests/alerts.yml:ro',
                '--entrypoint', '/bin/promtool', service['image'],
                'test', 'rules', '/tests/alerts.yml')
        dashboard = json.loads((ROOT / 'infra/grafana/dashboards/deepfake-business.json').read_text())
        rules = [{'record': 'dashboard_query_' + str(panel['id']) + '_' + target['refId'],
                  'expr': target['expr']} for panel in dashboard['panels']
                 for target in panel.get('targets', [])]
        with tempfile.TemporaryDirectory() as directory:
            queries = Path(directory) / 'dashboard.json'
            queries.write_text(json.dumps({'groups': [{'name': 'dashboard', 'rules': rules}]}))
            command('docker', 'run', '--rm', '--network', 'none',
                    '--volume', str(queries) + ':/tests/dashboard.json:ro',
                    '--entrypoint', '/bin/promtool', service['image'],
                    'check', 'rules', '/tests/dashboard.json')


if __name__ == '__main__':
    unittest.main(verbosity=2)
