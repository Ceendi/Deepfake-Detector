"""Guard the one-command run: Compose pulls only images that build-push.yml publishes.

Run with Python 3.9+ and the Docker Compose CLI: python3 infra/tests/test_compose_images.py
Only `docker compose config` is used; no image is pulled or built.
"""

import json
import os
import re
import subprocess
import unittest
from pathlib import Path

from test_discovery import ROOT, resolved_compose

REGISTRY = 'ghcr.io/ceendi/deepfake-detector/'
IMAGE = re.compile(re.escape(REGISTRY) + r'([a-z0-9-]+):(.+)$')


def compose_config(**environment):
    # Shell variables take precedence over the --env-file values in Compose interpolation.
    return json.loads(subprocess.check_output([
        'docker', 'compose', '--env-file', str(ROOT / '.env.example'),
        '--profile', '*', 'config', '--format', 'json',
    ], cwd=ROOT, text=True, env={**os.environ, **environment}))


def published_builds():
    """Build inputs of every image build-push.yml publishes, keyed by image name."""
    workflow = (ROOT / '.github/workflows/build-push.yml').read_text()
    matrix = workflow[workflow.index('      matrix:'):workflow.index('    steps:')]
    names = re.search(r'service: \[([^\]]+)\]', matrix).group(1).split(',')
    entries = {name.strip(): {} for name in names}
    for block in re.split(r'\n          - ', matrix)[1:]:
        fields = dict(re.findall(r'(?m)^\s*([a-z-]+): (\S+)$', block))
        entries.setdefault(fields.pop('service'), {}).update(fields)
    builds = {}
    for name, fields in entries.items():
        context = (ROOT / fields.get('context', './' + name)).resolve()
        builds[name] = {
            'context': context,
            'dockerfile': (ROOT / fields['file']).resolve() if 'file' in fields
            else context / 'Dockerfile',
            'contexts': {key: (ROOT / value).resolve() for key, value in (
                item.split('=', 1) for item in fields.get('build-contexts', '').split(',') if item)},
        }
    return builds


def compose_build(service):
    build = service['build']
    context = Path(build['context']).resolve()
    return {
        'context': context,
        'dockerfile': (context / build.get('dockerfile', 'Dockerfile')).resolve(),
        'contexts': {key: Path(value).resolve()
                     for key, value in build.get('additional_contexts', {}).items()},
    }


class ComposeImagesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.services = resolved_compose()['services']
        cls.built = {name: service for name, service in cls.services.items() if service.get('build')}

    def test_every_built_service_pulls_its_published_image_by_default(self):
        self.assertTrue(self.built)
        for name, service in self.built.items():
            with self.subTest(service=name):
                match = IMAGE.match(service.get('image', ''))
                self.assertIsNotNone(match, service.get('image'))
                self.assertEqual('latest', match.group(2))
                # Pull when absent; `up --build` still builds the checkout.
                self.assertEqual('missing', service.get('pull_policy'))

    def test_published_images_are_exactly_the_built_compose_images(self):
        builds = published_builds()
        images = {}
        for name, service in self.built.items():
            images.setdefault(IMAGE.match(service['image']).group(1), []).append(name)
        self.assertEqual(sorted(builds), sorted(images))
        for image, names in images.items():
            for name in names:
                with self.subTest(image=image, service=name):
                    self.assertEqual(builds[image], compose_build(self.services[name]))

    def test_tag_variable_selects_one_published_build_for_every_image(self):
        services = compose_config(DEEPFAKE_IMAGE_TAG='sha-0123abc')['services']
        for name, service in services.items():
            with self.subTest(service=name):
                if name in self.built:
                    self.assertTrue(service['image'].endswith(':sha-0123abc'), service['image'])
                else:
                    self.assertEqual(self.services[name]['image'], service['image'])
        self.assertRegex((ROOT / '.env.example').read_text(), r'(?m)^DEEPFAKE_IMAGE_TAG=latest$')

    def test_frontend_is_served_with_the_login_stack_on_loopback(self):
        frontend = self.services['frontend']
        self.assertEqual(['auth'], frontend['profiles'])
        self.assertTrue(frontend['read_only'])
        self.assertEqual(['ALL'], frontend['cap_drop'])
        [port] = frontend['ports']
        self.assertEqual(('127.0.0.1', 8080), (port['host_ip'], port['target']))
        # The browser must be sent to the same Keycloak URL that tokens are issued for.
        self.assertEqual(self.services['keycloak']['environment']['KC_HOSTNAME'],
                         frontend['environment']['KEYCLOAK_URL'])
        origin = 'http://localhost:' + port['published']
        for variant in ('realm-export.json', 'dev/realm-export.json'):
            realm = json.loads((ROOT / 'infra/keycloak' / variant).read_text())
            [client] = [client for client in realm['clients'] if client['clientId'] == 'deepfake-web']
            with self.subTest(realm=variant):
                self.assertIn(origin + '/*', client['redirectUris'])
                self.assertIn(origin, client['webOrigins'])

    def test_source_build_checks_never_run_published_images(self):
        e2e = (ROOT / '.github/workflows/e2e.yml').read_text()
        self.assertIn('run: $COMPOSE up -d --build --wait', e2e)
        keycloak = (ROOT / 'infra/tests/test_keycloak.py').read_text()
        self.assertIn("self.compose('up', '-d', '--build')", keycloak)


if __name__ == '__main__':
    unittest.main(verbosity=2)
