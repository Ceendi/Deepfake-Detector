"""Guard update coverage, immutable actions and infrastructure validation triggers."""

import re
import unittest

from test_discovery import ROOT


class CiReliabilityTest(unittest.TestCase):
    def test_actions_are_pinned_and_main_runs_are_not_cancelled(self):
        for name in ('ci', 'infra-smoke', 'security-scan'):
            workflow = (ROOT / f'.github/workflows/{name}.yml').read_text()
            with self.subTest(workflow=name):
                self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", workflow)
                for action in re.findall(r'(?m)^\s+(?:- )?uses: (\S+)', workflow):
                    self.assertRegex(action, r'@[0-9a-f]{40}$')

    def test_smoke_covers_monitoring_security_and_theme_changes_on_push_and_pr(self):
        workflow = (ROOT / '.github/workflows/infra-smoke.yml').read_text()
        for path in ('infra/rabbitmq/**', 'infra/security/**', 'infra/alloy/**',
                     'infra/loki/**', 'infra/grafana/**', 'frontend/dist_keycloak/**',
                     '.github/workflows/ci.yml'):
            with self.subTest(path=path):
                self.assertEqual(2, workflow.count('- ' + path))
        self.assertIn('python3 infra/tests/test_monitoring_configuration.py', workflow)
        self.assertIn('python3 infra/tests/test_ci_reliability.py', workflow)

    def test_dependabot_covers_runtime_and_application_manifests(self):
        config = (ROOT / '.github/dependabot.yml').read_text()
        blocks = re.split(r'(?m)^  - package-ecosystem: ', config)[1:]
        coverage = set()
        for block in blocks:
            ecosystem = block.splitlines()[0]
            directories = re.findall(r'(?m)^    directory: (.+)$|^      - (/\S+)$', block)
            coverage.update((ecosystem, first or second) for first, second in directories)
        for ecosystem, directories in (
            ('docker', ('video-detector', 'audio-detector')),
            ('uv', ('video-detector', 'audio-detector', 'detector-runtime')),
            ('maven', ('gateway', 'eureka-server', 'orchestrator', 'file-service')),
            ('npm', ('frontend',)),
        ):
            for directory in directories:
                self.assertIn((ecosystem, '/' + directory), coverage)
        maven = next(block for block in blocks if block.startswith('maven\n'))
        for group in ('org.springframework.boot:*', 'org.springframework.cloud:*'):
            self.assertIn(f'dependency-name: "{group}"', maven)
        self.assertIn('version-update:semver-major', maven)
        self.assertNotRegex(config, r'prefix: "infra"')

    def test_fixed_vulnerabilities_block_and_unfixed_findings_remain_reported(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertEqual(2, workflow.count('ignore-unfixed: true'))
        self.assertEqual(2, workflow.count("exit-code: '1'"))
        self.assertEqual(4, workflow.count('severity: HIGH,CRITICAL'))
        self.assertEqual(2, workflow.count('ignore-unfixed: false'))
        self.assertIn('format: json', workflow)
        self.assertIn('if: always()', workflow)

    def test_image_patches_accept_newer_revisions_and_assert_security_floor(self):
        alloy = (ROOT / 'infra/alloy/Dockerfile').read_text()
        self.assertIn('--only-upgrade', alloy)
        self.assertNotRegex(alloy, r'(?:libssl3t64|openssl)=')
        self.assertEqual(2, alloy.count('dpkg --compare-versions'))
        self.assertIn('3.0.13-0ubuntu3.16', alloy)
        postgres = (ROOT / 'infra/postgres/Dockerfile').read_text()
        self.assertNotIn('su-exec=', postgres)
        self.assertIn('apk upgrade --no-cache su-exec', postgres)
        self.assertIn('apk version -t', postgres)
        self.assertIn('apk info -v --installed su-exec', postgres)
        self.assertIn('0.3-r0', postgres)

    def test_checksum_adds_have_a_versioned_dockerfile_frontend(self):
        dockerfiles = [path for path in ROOT.rglob('Dockerfile')
                      if not any(part in ('.git', 'node_modules', '.venv', 'target')
                                 for part in path.parts)]
        for path in dockerfiles:
            source = path.read_text()
            if 'ADD --checksum=' in source:
                with self.subTest(dockerfile=str(path.relative_to(ROOT))):
                    self.assertEqual('# syntax=docker/dockerfile:1.7', source.splitlines()[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)
