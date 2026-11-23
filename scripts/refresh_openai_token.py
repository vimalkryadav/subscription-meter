#!/usr/bin/env python3
"""Sole refresher for the dedicated Codex (ChatGPT plan) login used by the gateway.

Reads the private Codex login record, renews it shortly before the access token
expires, and publishes only the current access token for the gateway to read.
The refresh token never leaves CODEX_HOME. Refresh tokens rotate, so no other
process (including an interactive Codex session) may use this CODEX_HOME.
Never prints tokens, provider response bodies or account identifiers.
"""
import argparse
import base64
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request

CODEX_HOME = Path(os.environ.get('CONTROL_USAGE_CODEX_HOME', '/home/coder/control-usage-openai/codex-home'))
GATEWAY_DIR = Path(os.environ.get('CONTROL_USAGE_OPENAI_TOKEN_DIR', '/home/coder/control-usage-openai/gateway-token'))
TOKEN_URL = 'https://auth.openai.com/oauth/token'
REFRESH_BEFORE_SECONDS = 48 * 3600
TOKEN_SHAPE = re.compile(r'[A-Za-z0-9._~+/-]+=*')
MAX_RESPONSE_BYTES = 65536


class RefreshError(Exception):
    """Sanitized failure; the message is safe to log."""
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def claims(jwt):
    try:
        payload = jwt.split('.')[1]
        payload += '=' * (-len(payload) % 4)
        value = json.loads(base64.urlsafe_b64decode(payload))
        if not isinstance(value, dict): raise ValueError
        return value
    except (IndexError, ValueError, TypeError):
        raise RefreshError('invalid_login', 'Login record contains a malformed token') from None


def client_id(tokens):
    audience = claims(tokens['id_token']).get('aud')
    if isinstance(audience, list): audience = audience[0] if audience else None
    if not isinstance(audience, str) or not audience:
        raise RefreshError('invalid_login', 'Login record has no OAuth client')
    return audience


def valid_token(value):
    return isinstance(value, str) and 0 < len(value) <= 16384 and TOKEN_SHAPE.fullmatch(value) \
        and not value.startswith(('sk-', 'sm_'))


def write_atomic(path, data, mode):
    # Same-directory temp file plus rename: readers see the old or the new file, never a partial one.
    fd, temp = tempfile.mkstemp(dir=path.parent, prefix='.' + path.name + '.')
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'w') as handle:
            handle.write(data); handle.flush(); os.fsync(handle.fileno())
        os.replace(temp, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try: os.fsync(directory)
        finally: os.close(directory)
    except BaseException:
        try: os.unlink(temp)
        except FileNotFoundError: pass
        raise


def load(home):
    try: record = json.loads((home / 'auth.json').read_text())
    except FileNotFoundError: raise RefreshError('login_required', 'No Codex login record; sign in again') from None
    except ValueError: raise RefreshError('invalid_login', 'Login record is not valid JSON') from None
    tokens = record.get('tokens') if isinstance(record, dict) else None
    if not isinstance(tokens, dict) or not all(valid_token(tokens.get(k)) for k in ('access_token', 'refresh_token', 'id_token')):
        raise RefreshError('login_required', 'Login record is not a ChatGPT login; sign in again')
    return record


def post_refresh(url, body, timeout=30):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), method='POST',
                                     headers={'Content-Type': 'application/json', 'Accept': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        # 400/401 from the token endpoint means the refresh token was rejected or already rotated.
        if error.code in (400, 401): raise RefreshError('login_required', f'Refresh rejected (HTTP {error.code}); sign in again') from None
        raise RefreshError('provider_error', f'Token endpoint returned HTTP {error.code}') from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise RefreshError('network_error', 'Token endpoint unreachable') from None
    if len(raw) > MAX_RESPONSE_BYTES: raise RefreshError('provider_error', 'Token response too large')
    try: return json.loads(raw)
    except ValueError: raise RefreshError('provider_error', 'Token response is not JSON') from None


def refresh(record, post=post_refresh, url=TOKEN_URL):
    tokens = record['tokens']
    result = post(url, {'client_id': client_id(tokens), 'grant_type': 'refresh_token',
                        'refresh_token': tokens['refresh_token'], 'scope': 'openid profile email'})
    if not isinstance(result, dict) or not valid_token(result.get('access_token')):
        raise RefreshError('provider_error', 'Token response has no usable access token')
    updated = dict(tokens)
    updated['access_token'] = result['access_token']
    for key in ('id_token', 'refresh_token'):
        if result.get(key) is not None:
            if not valid_token(result[key]): raise RefreshError('provider_error', 'Token response is malformed')
            updated[key] = result[key]
    return {**record, 'tokens': updated, 'last_refresh': datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')}


def publish(gateway_dir, access_token):
    gateway_dir.mkdir(mode=0o755, exist_ok=True)
    target = gateway_dir / 'access-token'
    try:
        if target.read_text() == access_token + '\n': return False
    except FileNotFoundError: pass
    # Readable by the unprivileged container user through a read-only directory mount;
    # the 0700 parent keeps it private on the host.
    write_atomic(target, access_token + '\n', 0o644)
    return True


@contextmanager
def exclusive(home):
    with (home / 'gateway-refresh.lock').open('w') as lock:
        try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise RefreshError('busy', 'Another refresh is running') from None
        yield


def run(home=CODEX_HOME, gateway_dir=GATEWAY_DIR, force=False, now=None, post=post_refresh):
    now = time.time() if now is None else now
    with exclusive(home):
        record = load(home)
        expires = claims(record['tokens']['access_token']).get('exp')
        if not isinstance(expires, (int, float)): raise RefreshError('invalid_login', 'Access token has no expiry')
        action = 'unchanged'
        if force or expires - now < REFRESH_BEFORE_SECONDS:
            record = refresh(record, post)
            write_atomic(home / 'auth.json', json.dumps(record, indent=2) + '\n', 0o600)
            action = 'refreshed'
            expires = claims(record['tokens']['access_token']).get('exp')
            if not isinstance(expires, (int, float)): raise RefreshError('provider_error', 'New access token has no expiry')
        if expires <= now: raise RefreshError('expired', 'Access token is expired')
        published = publish(gateway_dir, record['tokens']['access_token'])
        return {'status': 'ok', 'action': action, 'published': published,
                'hoursRemaining': round((expires - now) / 3600, 1)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--force', action='store_true', help='refresh now even if the access token is not near expiry')
    args = parser.parse_args(argv)
    try:
        result = run(force=args.force)
        print(f"OpenAI login: ok; action={result['action']}; published={str(result['published']).lower()}; "
              f"hoursRemaining={result['hoursRemaining']}")
        return 0
    except RefreshError as error:
        print(f'OpenAI login: {error.status}; {error}', file=sys.stderr)
        return 0 if error.status == 'busy' else 1
    except Exception as error:
        # Never echo exception text: it could include file contents.
        print(f'OpenAI login: failed; unexpected {type(error).__name__}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
