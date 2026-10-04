"""Check that the CI gate handles multiple targets and unfixed findings."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPORT = Path(__file__).resolve().parents[1] / 'security/report.py'


class SecurityReportTest(unittest.TestCase):
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
