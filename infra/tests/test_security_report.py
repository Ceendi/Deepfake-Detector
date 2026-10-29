"""Check that the CI gate handles multiple targets and unfixed findings."""

import json
from contextlib import redirect_stdout
from datetime import datetime, timezone
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPORT = Path(__file__).resolve().parents[1] / 'security/report.py'
SPEC = importlib.util.spec_from_file_location('security_report', REPORT)
security_report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(security_report)

IMAGE = 'grafana/grafana:13.2.5'
DIGEST = 'grafana/grafana@sha256:9f7aa1e1e3246aade5792d35a738049f7f32f89cd51d1956cf426a4e86268a84'
TARGET = 'usr/share/grafana/data/plugins-bundled/loki/gpx_grafana-loki-datasource_linux_amd64'
BEFORE_EXPIRY = datetime(2026, 11, 12, 22, 59, 59, tzinfo=timezone.utc)


def known_risk():
    return {'image': IMAGE, 'digest': DIGEST, 'vulnerability': 'CVE-2026-56857',
            'package': 'stdlib', 'installed_version': 'v1.26.7', 'targets': [TARGET],
            'expires_at': '2026-11-13T00:00:00+01:00',
            'reason': 'Owner-approved temporary acceptance pending upstream plugin fixes.'}


def grafana_report():
    return {'ArtifactName': IMAGE, 'Metadata': {'RepoDigests': [DIGEST]}, 'Results': [
        {'Target': TARGET, 'Vulnerabilities': [{'Severity': 'CRITICAL',
         'VulnerabilityID': 'CVE-2026-56857', 'PkgName': 'stdlib',
         'InstalledVersion': 'v1.26.7', 'FixedVersion': '1.26.9, 1.27.2'}]}]}


class SecurityReportTest(unittest.TestCase):
    def evaluate(self, report, risks=None, now=BEFORE_EXPIRY):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'trivy.json'
            original = json.dumps(report)
            path.write_text(original)
            arguments = [str(path)]
            if risks is not None:
                policy = Path(directory) / 'accepted-risks.json'
                policy.write_text(json.dumps(risks))
                arguments.extend(['--accepted-risks', str(policy)])
            output = io.StringIO()
            with redirect_stdout(output):
                code = security_report.main(arguments, now=now)
            self.assertEqual(original, path.read_text(), 'raw scan findings must remain unchanged')
            return code, output.getvalue()

    def test_accepted_risk_warns_and_keeps_the_critical_finding_visible(self):
        code, output = self.evaluate(grafana_report(), [known_risk()])
        self.assertEqual(0, code)
        self.assertIn('CRITICAL: 1;', output)
        self.assertIn('Accepted CRITICAL: 1; blocking CRITICAL: 0.', output)
        self.assertIn('::warning::', output)
        self.assertIn('CVE-2026-56857', output)
        self.assertIn('2026-11-13T00:00:00+01:00', output)

    def test_acceptance_expires_at_midnight_after_12_november_in_warsaw(self):
        for now in (datetime(2026, 11, 12, 23, 0, tzinfo=timezone.utc),
                    datetime(2026, 11, 13, 12, 0, tzinfo=timezone.utc)):
            with self.subTest(now=now):
                code, output = self.evaluate(grafana_report(), [known_risk()], now)
                self.assertEqual(1, code)
                self.assertIn('Accepted CRITICAL: 0; blocking CRITICAL: 1.', output)

    def test_acceptance_requires_the_exact_image_digest_target_package_and_version(self):
        for field in ('image', 'digest', 'vulnerability', 'package', 'installed_version', 'targets'):
            risk = known_risk()
            risk[field] = ['different-target'] if field == 'targets' else 'different-value'
            with self.subTest(field=field):
                code, _ = self.evaluate(grafana_report(), [risk])
                self.assertEqual(1, code)
        report = grafana_report()
        report.pop('Metadata')
        self.assertEqual(1, self.evaluate(report, [known_risk()])[0])

    def test_new_critical_finding_still_blocks_an_accepted_image(self):
        report = grafana_report()
        vulnerability = dict(report['Results'][0]['Vulnerabilities'][0])
        vulnerability['VulnerabilityID'] = 'CVE-new-critical'
        report['Results'][0]['Vulnerabilities'].append(vulnerability)
        code, output = self.evaluate(report, [known_risk()])
        self.assertEqual(1, code)
        self.assertIn('Accepted CRITICAL: 1; blocking CRITICAL: 1.', output)

    def test_publishing_without_an_explicit_policy_stays_strict(self):
        self.assertEqual(1, self.evaluate(grafana_report())[0])

    def test_expiry_without_a_timezone_is_rejected(self):
        risk = known_risk()
        risk['expires_at'] = '2026-11-13T00:00:00'
        with self.assertRaises(ValueError):
            self.evaluate(grafana_report(), [risk])

    def test_repository_policy_covers_only_the_reviewed_grafana_findings(self):
        risks = json.loads((REPORT.parent / 'accepted-risks.json').read_text())
        self.assertEqual(1, len(risks))
        for key in ('image', 'digest', 'vulnerability', 'package', 'installed_version', 'expires_at'):
            self.assertEqual(known_risk()[key], risks[0][key], key)
        self.assertEqual(6, len(set(risks[0]['targets'])))
        for target in risks[0]['targets']:
            self.assertTrue(target.startswith('usr/share/grafana/data/plugins-bundled/'))
            self.assertTrue(target.endswith('_linux_amd64'))
            self.assertNotIn('*', target)
            report = grafana_report()
            report['Results'][0]['Target'] = target
            self.assertEqual(0, self.evaluate(report, risks)[0])

    def test_only_compose_gate_accepts_risks_and_raw_sarif_is_not_filtered(self):
        root = REPORT.parents[2]
        workflow = (root / '.github/workflows/security-scan.yml').read_text()
        self.assertIn('report.py trivy.json --accepted-risks infra/security/accepted-risks.json', workflow)
        self.assertNotIn('continue-on-error:', workflow)
        self.assertNotIn('ignorefile:', workflow)
        self.assertIn('format: sarif', workflow)
        self.assertEqual(2, workflow.count('ignore-unfixed: false'))
        publishing = (root / '.github/workflows/build-push.yml').read_text()
        self.assertIn('python3 infra/security/report.py trivy.json', publishing)
        self.assertNotIn('--accepted-risks', publishing)

    def run_report(self, results):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'trivy.json'
            path.write_text(json.dumps({'ArtifactName': 'test-image', 'Results': results}))
            return subprocess.run([sys.executable, str(REPORT), str(path)], text=True, capture_output=True, check=False)

    def test_critical_finding_blocks_even_when_unfixed(self):
        result = self.run_report([
            {'Target': 'os', 'Vulnerabilities': [{
                'Severity': 'HIGH', 'VulnerabilityID': 'CVE-test-high',
                'PkgName': 'example', 'InstalledVersion': '1', 'FixedVersion': '2',
            }]},
            {'Target': 'runtime', 'Vulnerabilities': [{
                'Severity': 'CRITICAL', 'VulnerabilityID': 'CVE-test-critical',
                'PkgName': 'runtime', 'InstalledVersion': '1',
            }]},
        ])
        self.assertEqual(1, result.returncode)
        self.assertIn('CRITICAL: 1; HIGH: 1; without a listed fix: 1.', result.stdout)
        self.assertIn('CVE-test-critical', result.stdout)
        self.assertIn('No vendor fix listed', result.stdout)

    def test_empty_and_null_results_do_not_block(self):
        result = self.run_report([{'Target': 'os', 'Vulnerabilities': None}, {'Target': 'runtime'}])
        self.assertEqual(0, result.returncode)
        self.assertIn('CRITICAL: 0; HIGH: 0', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
