"""Guard update coverage, immutable actions and infrastructure validation triggers."""

import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path

from test_discovery import ROOT


class CiReliabilityTest(unittest.TestCase):
    def test_infrastructure_sources_and_benchmark_image_are_immutable(self):
        dockerfile = (ROOT / 'infra/keycloak-config-cli/Dockerfile').read_text()
        self.assertNotIn('api.github.com', dockerfile)
        self.assertRegex(dockerfile, r'ADD --checksum=sha256:[0-9a-f]{64} '
                                    r'https://codeload.github.com/adorsys/keycloak-config-cli/'
                                    r'legacy.tar.gz/[0-9a-f]{40} ')
    def test_benchmark_image_is_immutable(self):
        benchmark = (ROOT / 'load-tests/bench/bench-http.ps1').read_text()
        self.assertRegex(benchmark, r"'williamyeh/wrk@sha256:[0-9a-f]{64}'")

    def test_live_harness_regressions_run_when_the_harness_changes(self):
        self.assertIn('tests/live/**', json.loads((ROOT / 'infra/ci/workflow_paths.json').read_text())['infra-smoke'])
        self.assertIn('test_live_acceptance', (ROOT / 'infra/tests/run_static.py').read_text())

    def test_actions_are_pinned_and_main_runs_are_not_cancelled(self):
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            name = path.stem
            workflow = path.read_text()
            with self.subTest(workflow=name):
                self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", workflow)
                for action in re.findall(r'(?m)^\s+(?:- )?uses: (\S+)', workflow):
                    self.assertRegex(action, r'@[0-9a-f]{40}$')

    def test_smoke_covers_monitoring_security_and_theme_changes_on_push_and_pr(self):
        workflow = (ROOT / '.github/workflows/infra-smoke.yml').read_text()
        for path in ('infra/rabbitmq/**', 'infra/security/**', 'infra/alloy/**',
                     'infra/loki/**', 'infra/grafana/**', 'frontend/src/keycloak-theme/**', 'frontend/package.json', 'frontend/package-lock.json',
                     'frontend/vite.config.ts', 'frontend/src/styles/**', 'frontend/src/index.tsx',
                     '.github/workflows/ci.yml'):
            with self.subTest(path=path):
                self.assertIn(path, json.loads((ROOT / 'infra/ci/workflow_paths.json').read_text())['infra-smoke'])
        self.assertIn('python3 infra/tests/test_monitoring_configuration.py', workflow)
        self.assertIn('test_ci_reliability', (ROOT / 'infra/tests/run_static.py').read_text())

    def test_dependabot_covers_runtime_and_application_manifests(self):
        config = (ROOT / '.github/dependabot.yml').read_text()
        blocks = re.split(r'(?m)^  - package-ecosystem: ', config)[1:]
        coverage = set()
        for block in blocks:
            ecosystem = block.splitlines()[0]
            directories = re.findall(r'(?m)^    directory: (.+)$|^      - (/\S+)$', block)
            coverage.update((ecosystem, first or second) for first, second in directories)
        for ecosystem, directories in (
            ('docker', ('video-detector', 'audio-detector', 'frontend')),
            ('uv', ('video-detector', 'audio-detector', 'detector-runtime')),
            ('maven', ('gateway', 'eureka-server', 'orchestrator', 'file-service')),
            ('npm', ('frontend',)),
            ('pip', ('infra/ci', 'infra/ci/test-requirements')),
        ):
            for directory in directories:
                self.assertIn((ecosystem, '/' + directory), coverage)
        maven = next(block for block in blocks if block.startswith('maven\n'))
        for group in ('org.springframework.boot:*', 'org.springframework.cloud:*'):
            self.assertIn(f'dependency-name: "{group}"', maven)
        self.assertIn('version-update:semver-major', maven)
        self.assertNotRegex(config, r'prefix: "infra"')

    def test_ci_python_fixtures_install_only_hash_verified_wheels(self):
        pins = (ROOT / 'infra/ci/requirements.txt').read_text()
        self.assertIn('--generate-hashes', pins.splitlines()[1])
        for requirement in re.split(r'\n(?=[a-z])', pins[pins.index('\n', pins.index('compile')) + 1:]):
            with self.subTest(requirement=requirement.splitlines()[0]):
                self.assertRegex(requirement, r'^[a-z0-9._-]+==\S+')
                self.assertRegex(requirement, r'--hash=sha256:[0-9a-f]{64}')
        light = (ROOT / 'audio-detector/tests/requirements-light.txt').read_text()
        self.assertEqual(['-r ../../infra/ci/requirements.txt'],
                         [line for line in light.splitlines() if not line.startswith('#')])
        installs = 0
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            for line in path.read_text().splitlines():
                if 'requirements-light.txt' in line or 'infra/ci/requirements.txt' in line:
                    installs += 1
                    with self.subTest(workflow=path.name, line=line.strip()):
                        self.assertIn('pip install', line)
                        self.assertIn('--require-hashes', line)
                        self.assertIn("--only-binary ':all:'", line)
        self.assertEqual(4, installs)

    def test_runtime_link_points_the_interpreter_at_the_repository_package(self):
        spec = importlib.util.spec_from_file_location('link', ROOT / 'infra/ci/link_detector_runtime.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            target = module.link(directory)
            self.assertEqual(Path(directory) / 'deepfake-detector-runtime.pth', target)
            self.assertEqual(ROOT / 'detector-runtime', Path(target.read_text().strip()))
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            workflow = path.read_text()
            self.assertEqual(workflow.count('requirements-light.txt'),
                             workflow.count('python infra/ci/link_detector_runtime.py'), path.name)

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

    def test_alpine_runtime_images_take_package_fixes_released_after_the_base(self):
        # Trivy fails the build on CRITICAL Alpine fixes the base image does not ship yet.
        for name in ('gateway', 'eureka-server', 'orchestrator', 'file-service', 'frontend',
                     'infra/postgres', 'infra/keycloak-config-cli'):
            runtime = (ROOT / name / 'Dockerfile').read_text().rsplit('\nFROM ', 1)[-1]
            # A bare upgrade, not one limited to named packages, before dropping root.
            upgrade = re.search(r'apk upgrade --no-cache(?= *(?:&&|\\?\n))', runtime)
            with self.subTest(image=name):
                self.assertIsNotNone(upgrade)
                self.assertLess(upgrade.start(), runtime.rindex('\nUSER '))

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
