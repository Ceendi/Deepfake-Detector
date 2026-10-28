"""Guard the deployment defaults and privileged image publishing boundary."""

import json
import re
import unittest

from test_discovery import ROOT, resolved_compose

JAVA_SERVICES = ('gateway', 'eureka-server', 'orchestrator', 'file-service')


class SecurityConfigurationTest(unittest.TestCase):
    def test_password_reset_is_disabled_until_smtp_is_configured(self):
        for variant in ('realm-export.json', 'dev/realm-export.json'):
            with self.subTest(variant=variant):
                realm = json.loads((ROOT / 'infra/keycloak' / variant).read_text())
                self.assertFalse(realm['resetPasswordAllowed'])

    def test_default_realm_has_no_fixture_users_or_service_accounts(self):
        realm = json.loads((ROOT / 'infra/keycloak/realm-export.json').read_text())
        self.assertFalse(realm.get('users'))
        self.assertEqual(['deepfake-web', 'deepfake-cli'], [client['clientId'] for client in realm['clients']])
        self.assertFalse(any(client.get('serviceAccountsEnabled') for client in realm['clients']))

    def test_cli_client_only_allows_the_device_grant(self):
        for variant in ('realm-export.json', 'dev/realm-export.json'):
            realm = json.loads((ROOT / 'infra/keycloak' / variant).read_text())
            [client] = [client for client in realm['clients'] if client['clientId'] == 'deepfake-cli']
            with self.subTest(variant=variant):
                self.assertTrue(client['publicClient'])
                self.assertNotIn('secret', client)
                for flow in ('standardFlowEnabled', 'directAccessGrantsEnabled', 'implicitFlowEnabled',
                             'serviceAccountsEnabled'):
                    self.assertIs(False, client[flow], flow)
                self.assertEqual({'oauth2.device.authorization.grant.enabled': 'true'}, client['attributes'])
                # Roles come from the realm's default client scopes, as for deepfake-web.
                self.assertNotIn('defaultClientScopes', client)
                self.assertNotIn('protocolMappers', client)

    def test_cli_client_is_accepted_by_the_api(self):
        defaults = {
            '.env.example': r'(?m)^JWT_ALLOWED_CLIENTS=(.+)$',
            'docker-compose.yml': r'JWT_ALLOWED_CLIENTS: \$\{JWT_ALLOWED_CLIENTS:-([^}]+)\}',
            'orchestrator/src/main/resources/application.yaml': r'allowed-clients: \$\{JWT_ALLOWED_CLIENTS:([^}]+)\}',
            'file-service/src/main/resources/application.yaml': r'allowed-clients: \$\{JWT_ALLOWED_CLIENTS:([^}]+)\}',
            'orchestrator/src/main/java/com/deepfake/orchestrator/config/SecurityConfig.java':
                r'security\.jwt\.allowed-clients:([^}]+)\}',
            'file-service/src/main/java/com/deepfake/fileservice/config/SecurityConfig.java':
                r'security\.jwt\.allowed-clients:([^}]+)\}',
        }
        for path, pattern in defaults.items():
            with self.subTest(path=path):
                [clients] = re.findall(pattern, (ROOT / path).read_text())
                self.assertEqual({'deepfake-web', 'deepfake-cli', 'deepfake-loadtest'}, set(clients.split(',')))

    def test_default_import_selects_only_the_production_realm(self):
        service = resolved_compose()['services']['keycloak-config-cli']
        self.assertEqual('/config/realm-export.json', service['environment']['IMPORT_FILES_LOCATIONS'])

    def test_development_realm_only_adds_test_identities(self):
        production = json.loads((ROOT / 'infra/keycloak/realm-export.json').read_text())
        development = json.loads((ROOT / 'infra/keycloak/dev/realm-export.json').read_text())
        users = development.pop('users')
        self.assertEqual({'alice', 'bob', 'service-account-deepfake-loadtest'},
                         {user['username'] for user in users})
        loadtest = [client for client in development['clients'] if client['clientId'] == 'deepfake-loadtest']
        self.assertEqual(1, len(loadtest))
        development['clients'].remove(loadtest[0])
        self.assertEqual(production, development, 'Production policy must also apply to the dev realm')

    def test_gateway_management_port_is_internal_and_scraped(self):
        service = resolved_compose()['services']['gateway']
        self.assertEqual([8080], [port['target'] for port in service.get('ports', [])])
        properties = (ROOT / 'gateway/src/main/resources/application.yaml').read_text()
        self.assertRegex(properties, r'management:\s+server:\s+port: 9090')
        self.assertIn('gateway:9090', (ROOT / 'infra/prometheus/prometheus.yml').read_text())

    def test_java_images_are_in_the_compose_scan_inventory(self):
        services = resolved_compose()['services']
        for name in JAVA_SERVICES:
            with self.subTest(service=name):
                self.assertTrue(services[name].get('build'))
                self.assertTrue(services[name].get('image'))

    def test_java_base_images_receive_dependabot_updates(self):
        config = (ROOT / '.github/dependabot.yml').read_text()
        blocks = re.split(r'(?m)^  - package-ecosystem: ', config)[1:]
        directories = set()
        for block in blocks:
            if block.splitlines()[0] == 'docker':
                directories.update(re.findall(r'(?m)^    directory: (.+)$', block))
        self.assertTrue({'/' + name for name in JAVA_SERVICES} <= directories, directories)

    def test_every_action_in_the_publishing_job_is_immutable(self):
        workflow = (ROOT / '.github/workflows/build-push.yml').read_text()
        self.assertIn('packages: write', workflow)
        actions = re.findall(r'(?m)^\s+(?:- )?uses: (\S+)(.*)$', workflow)
        self.assertTrue(actions)
        for action, comment in actions:
            with self.subTest(action=action):
                self.assertRegex(action, r'@[0-9a-f]{40}$')
                self.assertRegex(comment, r'# v\d+\.\d+\.\d+')

    def test_published_images_are_scanned_before_push_and_attested(self):
        workflow = (ROOT / '.github/workflows/build-push.yml').read_text()
        scan = workflow.index('aquasecurity/trivy-action@')
        gate = workflow.index('infra/security/report.py')
        push = workflow.index('push: true')
        attest = workflow.index('actions/attest@')
        self.assertLess(scan, gate)
        self.assertLess(gate, push)
        self.assertLess(push, attest)
        self.assertEqual(1, workflow.count('push: true'))
        self.assertIn('subject-digest: ${{ steps.build.outputs.digest }}', workflow)
        self.assertIn('push-to-registry: true', workflow)

    def test_detector_images_load_their_pinned_models_before_push(self):
        workflow = (ROOT / '.github/workflows/build-push.yml').read_text()
        smoke = workflow.index('infra/ci:/smoke:ro')
        self.assertLess(workflow.index('aquasecurity/trivy-action@'), smoke)
        self.assertLess(smoke, workflow.index('push: true'))
        self.assertIn('models: video', workflow)
        self.assertIn('models: audio', workflow)
        self.assertIn('"*/models.toml"', workflow)
        for detector in ('video-detector', 'audio-detector'):
            manifest = (ROOT / detector / 'models.toml').read_text()
            self.assertRegex(manifest, r'(?m)^revision = "[0-9a-f]{40}"$')
            self.assertIn('COPY models.toml', (ROOT / detector / 'Dockerfile').read_text())
            self.assertIn('training/checkpoints/', (ROOT / detector / '.dockerignore').read_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
