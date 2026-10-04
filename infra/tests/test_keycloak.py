"""Test the Compose identity provider and the CVE-2026-18963 reset flow."""

import base64
import hashlib
import html
import http.cookiejar
import json
import os
import re
import socket
import subprocess
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin
from urllib.request import HTTPCookieProcessor, Request, build_opener, urlopen

ROOT = Path(__file__).resolve().parents[2]


class LocalBrowserCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):
        # Browsers accept Secure cookies on http://localhost as a trustworthy
        # origin. urllib needs the same behavior for this disposable test server.
        if request.host.split(':')[0] == 'localhost':
            return True
        return super().return_ok_secure(cookie, request)


class KeycloakSecurityTest(unittest.TestCase):
    def compose(self, *args):
        return subprocess.check_output(
            ['docker', 'compose', '-f', str(self.compose_file), *args],
            cwd=ROOT, text=True, stderr=subprocess.STDOUT,
        ).strip()

    def request(self, path, payload=None, method=None, token=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        data = None if payload is None else json.dumps(payload).encode()
        with urlopen(Request(self.base + path, data=data, headers=headers, method=method), timeout=10) as response:
            body = response.read().decode()
            return json.loads(body) if body else None

    def eventually(self, check, timeout=180):
        deadline = time.monotonic() + timeout
        last_error = None
        while time.monotonic() < deadline:
            try:
                return check()
            except (URLError, AssertionError, OSError) as error:
                last_error = error
                if isinstance(error, HTTPError):
                    error.close()
                time.sleep(2)
        self.fail(f'Identity provider did not satisfy the check: {last_error}')

    def token(self, realm, fields):
        request = Request(
            self.base + f'/realms/{realm}/protocol/openid-connect/token',
            data=urlencode(fields).encode(),
            headers={'Content-Type': 'application/x-www-form-urlencoded'},
        )
        with urlopen(request, timeout=10) as response:
            return json.load(response)['access_token']

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='keycloak-security-')
        self.addCleanup(self.directory.cleanup)
        self.compose_file = Path(self.directory.name) / 'compose.json'
        project = 'keycloak-test-' + uuid.uuid4().hex[:12]
        config = json.loads(subprocess.check_output(
            ['docker', 'compose', '--env-file', str(ROOT / '.env.example'),
             '--profile', '*', 'config', '--format', 'json'], cwd=ROOT, text=True,
        ))
        services = {name: config['services'][name] for name in ('keycloak', 'keycloak-db', 'keycloak-config-cli')}
        # Optional vulnerable-version control; CI always tests the Compose image.
        if os.environ.get('KEYCLOAK_TEST_IMAGE'):
            services['keycloak']['image'] = os.environ['KEYCLOAK_TEST_IMAGE']
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        self.base = f'http://localhost:{port}'
        for service in services.values():
            service.pop('profiles', None)
            service['restart'] = 'no'
        services['keycloak']['ports'] = [{'target': 8080, 'published': str(port), 'host_ip': '127.0.0.1'}]
        services['keycloak']['environment']['KC_HOSTNAME'] = self.base
        # SMTP never leaves this disposable network; all mail goes to the sink.
        services['smtp'] = {
            'image': 'axllent/mailpit:v1.31.4',
            'ports': [{'target': 8025, 'published': '0', 'host_ip': '127.0.0.1'}],
            'networks': ['deepfake'],
        }
        config = {
            'name': project, 'services': services,
            'volumes': {'keycloak-pgdata': {'name': project + '-database'}},
            'networks': {'deepfake': {'name': project + '-network'}},
        }
        self.compose_file.write_text(json.dumps(config))
        self.addCleanup(self.compose, 'down', '--volumes', '--remove-orphans')
        self.addCleanup(self.failure_logs)
        self.compose('up', '-d')
        self.admin = self.eventually(lambda: self.token('master', {
            'grant_type': 'password', 'client_id': 'admin-cli',
            'username': 'admin', 'password': 'changeme_dev',
        }))
        self.eventually(lambda: self.request('/realms/deepfake'))
        def assert_import_finished():
            status = json.loads(self.compose('ps', '-a', '--format', 'json', 'keycloak-config-cli'))
            if isinstance(status, list):
                status = status[0]
            self.assertEqual('exited', status['State'])
            self.assertEqual(0, status['ExitCode'])

        self.eventually(assert_import_finished)
        self.admin = self.token('master', {
            'grant_type': 'password', 'client_id': 'admin-cli',
            'username': 'admin', 'password': 'changeme_dev',
        })

    def failure_logs(self):
        result = self._outcome.result
        if any(test is self for test, _ in result.failures + result.errors):
            print(self.compose('logs', '--tail', '80', 'keycloak', 'keycloak-config-cli'))

    def browser_get(self, url):
        with self.browser.open(url, timeout=10) as response:
            return response.geturl(), response.read().decode()

    def browser_post(self, url, fields):
        request = Request(url, data=urlencode(fields).encode())
        with self.browser.open(request, timeout=10) as response:
            return response.geturl(), response.read().decode()

    def form_action(self, page):
        match = re.search(r'<form\b[^>]*\baction="([^"]+)"', page)
        self.assertIsNotNone(match, 'Expected a form in the reset flow')
        return html.unescape(match.group(1))

    def test_reset_requires_email_proof_and_existing_clients_work(self):
        token = self.token('deepfake', {
            'grant_type': 'client_credentials', 'client_id': 'deepfake-loadtest',
            'client_secret': 'changeme_dev_loadtest',
        })
        encoded = token.split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        self.assertEqual(self.base + '/realms/deepfake', claims['iss'])
        self.assertIn('USER', claims['realm_access']['roles'])
        realm = self.request('/admin/realms/deepfake', token=self.admin)
        self.assertEqual('deepfake', realm['loginTheme'])
        self.assertTrue(realm['resetPasswordAllowed'])
        self.cookies = http.cookiejar.CookieJar(policy=LocalBrowserCookiePolicy())
        self.browser = build_opener(HTTPCookieProcessor(self.cookies))
        query = urlencode({
            'client_id': 'deepfake-web', 'redirect_uri': 'http://localhost:5173/',
            'response_type': 'code', 'scope': 'openid',
            'code_challenge': base64.urlsafe_b64encode(hashlib.sha256(b'regression-verifier').digest()).decode().rstrip('='),
            'code_challenge_method': 'S256',
        })
        login_url = self.base + '/realms/deepfake/protocol/openid-connect/auth?' + query
        _, themed_login = self.browser_get(login_url)
        self.assertIn('kcContext', themed_login)
        # Use the built-in HTML forms to exercise the server-side flow without a JS browser.
        # This only changes the disposable realm, not the repository's theme configuration.
        self.request('/admin/realms/deepfake', {
            'loginTheme': 'keycloak',
            'smtpServer': {'host': 'smtp', 'port': '1025', 'from': 'security-test@example.invalid'},
        }, method='PUT', token=self.admin)
        self.cookies = http.cookiejar.CookieJar(policy=LocalBrowserCookiePolicy())
        self.browser = build_opener(HTTPCookieProcessor(self.cookies))
        _, login = self.browser_get(login_url)
        match = re.search(r'href="([^"]*login-actions/reset-credentials[^"]*)"', login)
        self.assertIsNotNone(match)
        try:
            reset_url, reset = self.browser_get(urljoin(self.base, html.unescape(match.group(1))))
        except HTTPError as error:
            self.fail(f'Reset link rejected: {error.code}: {error.read().decode()}')
        # Replay the sequence from Keycloak's upstream regression test (#51844).
        _, selector = self.browser_post(self.form_action(reset), {'tryAnotherWay': 'on'})
        _, sent = self.browser_post(self.form_action(selector), {'username': 'alice'})
        self.assertIn('You should receive an email shortly', sent)
        smtp_api = 'http://' + self.compose('port', 'smtp', '8025') + '/api/v1/messages'
        def assert_email_delivered():
            with urlopen(smtp_api, timeout=10) as response:
                self.assertEqual(1, json.load(response)['total'])
        self.eventually(assert_email_delivered)
        _, revisited = self.browser_get(reset_url)
        self.assertIn('You should receive an email shortly', revisited)
        self.assertNotIn('name="password-new"', revisited)
        # No emailed action token has been consumed. Forcing the reset action must fail closed.
        try:
            _, forced = self.browser_post(self.form_action(selector), {
                'password-new': 'AttackerChosen123!@', 'password-confirm': 'AttackerChosen123!@',
            })
        except HTTPError as error:
            self.assertIn(error.code, (400, 403))
            error.close()
        else:
            self.assertNotIn('name="password-new"', forced)
        users = self.request('/admin/realms/deepfake/users?username=alice&exact=true', token=self.admin)
        self.assertEqual(1, len(users))
        # Verify the password was not changed with a test-only direct-grant client.
        self.request('/admin/realms/deepfake/clients', {
            'clientId': 'security-password-check', 'publicClient': True,
            'directAccessGrantsEnabled': True,
        }, method='POST', token=self.admin)
        self.assertTrue(self.token('deepfake', {
            'grant_type': 'password', 'client_id': 'security-password-check',
            'username': 'alice', 'password': 'Test1234!@',
        }))


if __name__ == '__main__':
    unittest.main(verbosity=2)
