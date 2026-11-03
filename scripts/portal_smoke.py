"""Exercise signup, approval, token delivery and account isolation through the public tunnel."""
import argparse
import http.cookiejar
import json
from pathlib import Path
import secrets
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('url')
base = parser.parse_args().url.rstrip('/')
url = urllib.parse.urlsplit(base)
assert url.scheme == 'https' and (url.hostname or '').endswith('.trycloudflare.com') and not url.path
assert not url.username and not url.query and not url.fragment
root = Path(__file__).resolve().parents[1]
admin = (root / 'secrets/admin-token').read_text().strip()


class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = ''

    def call(self, path, body=None, token=None, csrf=True, origin=base, custom=True):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if custom:
            headers['X-Portal-Request'] = '1'
        if csrf:
            headers['X-CSRF-Token'] = self.csrf
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(origin + path, data=data, headers=headers)
        try:
            response = self.opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            try:
                result = json.loads(raw)
            except ValueError:
                result = raw.decode()
            return response.status, result, response.headers


owner, alice, bob = Client(), Client(), Client()
assert owner.call('/admin/status', token=admin, origin='http://127.0.0.1:8180')[1]['mode'] == 'mock'
assert owner.call('/')[0] == 200
assert 'Create account' in owner.call('/')[1]
assert 'Member access requests' in owner.call('/manage')[1]
report = {'url': base, 'mode': 'mock'}
requests = []
accounts = []
try:
    for client in (alice, bob):
        username = 'portal-smoke-' + uuid.uuid4().hex[:10]
        password = secrets.token_urlsafe(24)
        body = {'username': username, 'displayName': 'Portal smoke test', 'password': password}
        assert client.call('/portal/api/register', body, custom=False)[0] == 403
        code, session, headers = client.call('/portal/api/register', body)
        assert code == 201, code
        assert all(part in headers['Set-Cookie'] for part in ('Secure', 'HttpOnly', 'SameSite=Strict', 'Path=/portal/api'))
        client.csrf = session['csrfToken']
        accounts.append(session['user']['id'])
        assert 'password' not in json.dumps(session).lower()
        assert client.call('/portal/api/login', {'username': username, 'password': 'incorrect password'})[0] == 401
        # Real password login rotates the signup session cookie.
        previous_cookie = next(iter(client.cookies)).value
        code, session, _ = client.call('/portal/api/login', {'username': username, 'password': password})
        assert code == 200 and next(iter(client.cookies)).value != previous_cookie
        client.csrf = session['csrfToken']
        assert client.call('/portal/api/session')[1]['user']['id'] == accounts[-1]
        assert client.call('/portal/api/token', {})[0] == 409
        assert client.call('/admin/key-requests')[0] == 401
    report['signup_login_and_session_rotation'] = True
    assert alice.call('/portal/api/key-requests', {'reason': 'CSRF check'}, csrf=False)[0] == 403
    code, result, _ = alice.call('/portal/api/key-requests', {'reason': 'Public portal acceptance test'})
    assert code == 200 and result['access']['state'] == 'pending'
    request_id = result['access']['request']['id']
    requests.append(request_id)
    assert alice.call('/portal/api/token', {})[0] == 409
    assert alice.call('/admin/key-requests/' + request_id + '/decision', {'approve': True, 'rpm': 60, 'concurrency': 4})[0] == 401
    queue = owner.call('/admin/key-requests', token=admin)[1]
    assert any(item['request']['id'] == request_id and item['state'] == 'pending' for item in queue)
    assert owner.call('/admin/key-requests/' + request_id + '/decision', {'approve': True, 'rpm': 60, 'concurrency': 4}, admin)[0] == 200
    assert alice.call('/portal/api/access')[1]['access']['state'] == 'approved'
    code, result, _ = alice.call('/portal/api/token', {})
    assert code == 200
    key = result['key']
    assert key.startswith('sm_')
    assert alice.call('/portal/api/token', {})[0] == 409
    assert bob.call('/portal/api/token', {'accountId': accounts[0]})[0] == 409
    assert bob.call('/portal/api/access?accountId=' + accounts[0])[1]['access']['state'] == 'none'
    assert owner.call('/portal/api/access', token=key)[0] == 401
    assert owner.call('/admin/key-requests', token=key)[0] == 401
    payload = {'model': 'mock-model', 'max_tokens': 16, 'stream': False, 'messages': [{'role': 'user', 'content': 'Portal acceptance test'}]}
    assert owner.call('/gateway/v1/messages', payload, key)[0] == 200
    own_access = alice.call('/portal/api/access')[1]
    assert own_access['access']['state'] == 'active' and len(own_access['access']['usage']) == 1
    assert key not in json.dumps(own_access)
    assert bob.call('/portal/api/access')[1]['access']['usage'] == []
    report['approval_one_time_issuance_and_usage_isolation'] = True
    rotated = alice.call('/portal/api/token/rotate', {})[1]['key']
    assert owner.call('/gateway/v1/models', token=key)[0] == 401
    assert owner.call('/gateway/v1/models', token=rotated)[0] == 200
    assert owner.call('/admin/key-requests/' + request_id + '/revoke', {}, admin)[0] == 200
    assert owner.call('/gateway/v1/models', token=rotated)[0] == 401
    assert alice.call('/portal/api/token/rotate', {})[0] == 409
    assert alice.call('/portal/api/access')[1]['access']['state'] == 'revoked'
    report['rotation_and_revocation'] = True
    assert alice.call('/portal/api/logout', {})[0] == 200
    assert alice.call('/portal/api/session')[0] == 401
    report['logout_and_csrf'] = True
finally:
    for request_id in requests:
        assert owner.call('/admin/key-requests/' + request_id + '/revoke', {}, admin, origin='http://127.0.0.1:8180')[0] == 200
    for client in (alice, bob):
        if client.csrf:
            client.call('/portal/api/logout', {})
report['test_accounts'] = accounts
(root / 'artifacts').mkdir(exist_ok=True)
(root / 'artifacts/portal-smoke.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
