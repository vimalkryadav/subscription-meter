import base64
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import refresh_openai_token as refresher

NOW = 1_800_000_000


def jwt(**claims):
    encode = lambda value: base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip('=')
    return encode({'alg': 'none'}) + '.' + encode(claims) + '.sig'


class RefreshTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.home = self.root / 'codex-home'; self.home.mkdir(mode=0o700)
        self.gateway = self.root / 'gateway-token'
        self.calls = []

    def login(self, hours_left, **extra):
        record = {'auth_mode': 'chatgpt', 'OPENAI_API_KEY': None, 'last_refresh': 'old',
                  'tokens': {'access_token': jwt(exp=NOW + hours_left * 3600), 'refresh_token': 'rt-old',
                             'id_token': jwt(aud=['app_client'], exp=NOW), 'account_id': 'acct'}, **extra}
        (self.home / 'auth.json').write_text(json.dumps(record))
        os.chmod(self.home / 'auth.json', 0o600)
        return record

    def post(self, response):
        def fake(url, body):
            self.calls.append((url, body))
            if isinstance(response, Exception): raise response
            return response
        return fake

    def stored(self): return json.loads((self.home / 'auth.json').read_text())

    def test_fresh_token_is_published_without_contacting_provider(self):
        record = self.login(200)
        result = refresher.run(self.home, self.gateway, now=NOW, post=self.post({}))
        self.assertEqual(self.calls, [])
        self.assertEqual(result['action'], 'unchanged')
        self.assertEqual((self.gateway / 'access-token').read_text(), record['tokens']['access_token'] + '\n')
        self.assertEqual((self.gateway / 'access-token').stat().st_mode & 0o777, 0o644)
        self.assertFalse(refresher.run(self.home, self.gateway, now=NOW, post=self.post({}))['published'])

    def test_near_expiry_rotates_tokens_and_preserves_other_fields(self):
        self.login(10)
        new_access = jwt(exp=NOW + 240 * 3600)
        result = refresher.run(self.home, self.gateway, now=NOW,
                               post=self.post({'access_token': new_access, 'refresh_token': 'rt-new', 'id_token': jwt(aud='app_client')}))
        url, body = self.calls[0]
        self.assertEqual(url, refresher.TOKEN_URL)
        self.assertEqual(body, {'client_id': 'app_client', 'grant_type': 'refresh_token',
                                'refresh_token': 'rt-old', 'scope': 'openid profile email'})
        stored = self.stored()
        self.assertEqual(stored['tokens']['refresh_token'], 'rt-new')
        self.assertEqual(stored['tokens']['access_token'], new_access)
        self.assertEqual(stored['tokens']['account_id'], 'acct')
        self.assertEqual(stored['auth_mode'], 'chatgpt')
        self.assertNotEqual(stored['last_refresh'], 'old')
        self.assertEqual((self.home / 'auth.json').stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.gateway / 'access-token').read_text(), new_access + '\n')
        self.assertEqual((result['action'], result['hoursRemaining']), ('refreshed', 240.0))
        self.assertNotIn('rt-', (self.gateway / 'access-token').read_text())

    def test_missing_rotated_refresh_token_keeps_existing_one(self):
        self.login(1)
        refresher.run(self.home, self.gateway, now=NOW, post=self.post({'access_token': jwt(exp=NOW + 99999)}))
        self.assertEqual(self.stored()['tokens']['refresh_token'], 'rt-old')

    def test_failures_leave_login_untouched_and_publish_nothing(self):
        for response, status in [(refresher.RefreshError('login_required', 'x'), 'login_required'),
                                 ({'access_token': 'sk-api-key'}, 'provider_error'),
                                 ({'access_token': jwt(exp=NOW - 1)}, 'expired'),
                                 ({}, 'provider_error')]:
            with self.subTest(status=status):
                before = json.dumps(self.login(1))
                with self.assertRaises(refresher.RefreshError) as caught:
                    refresher.run(self.home, self.gateway, now=NOW, post=self.post(response))
                self.assertEqual(caught.exception.status, status)
                if status != 'expired': self.assertEqual(json.loads((self.home / 'auth.json').read_text()), json.loads(before))
                self.assertFalse((self.gateway / 'access-token').exists())

    def test_api_key_login_and_missing_record_require_sign_in(self):
        with self.assertRaises(refresher.RefreshError) as caught: refresher.run(self.home, self.gateway, now=NOW)
        self.assertEqual(caught.exception.status, 'login_required')
        (self.home / 'auth.json').write_text(json.dumps({'auth_mode': 'apikey', 'OPENAI_API_KEY': 'sk-x', 'tokens': None}))
        with self.assertRaises(refresher.RefreshError) as caught: refresher.run(self.home, self.gateway, now=NOW)
        self.assertEqual(caught.exception.status, 'login_required')

    def test_main_never_prints_tokens(self):
        record = self.login(1)
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(refresher, 'CODEX_HOME', self.home), mock.patch.object(refresher, 'GATEWAY_DIR', self.gateway), \
             mock.patch.object(refresher, 'post_refresh', self.post(refresher.RefreshError('login_required', 'Refresh rejected (HTTP 401); sign in again'))), \
             mock.patch('sys.stdout', out), mock.patch('sys.stderr', err):
            run = refresher.run
            with mock.patch.object(refresher, 'run', lambda force=False: run(self.home, self.gateway, force, NOW, refresher.post_refresh)):
                self.assertEqual(refresher.main([]), 1)
        text = out.getvalue() + err.getvalue()
        self.assertIn('login_required', text)
        for secret in (record['tokens']['access_token'], 'rt-old', 'acct'): self.assertNotIn(secret, text)

    def test_http_rejection_is_sanitized(self):
        error = refresher.urllib.error.HTTPError(refresher.TOKEN_URL, 401, 'Unauthorized', {}, io.BytesIO(b'{"refresh_token":"leak"}'))
        with mock.patch('urllib.request.urlopen', side_effect=error):
            with self.assertRaises(refresher.RefreshError) as caught: refresher.post_refresh(refresher.TOKEN_URL, {})
        self.assertEqual(caught.exception.status, 'login_required')
        self.assertNotIn('leak', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
