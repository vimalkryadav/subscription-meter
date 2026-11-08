#!/usr/bin/env python3
"""Private, origin-bound administrator login for the local MCP bridge (Python 3.9+)."""
import argparse
from getpass import getpass
import http.cookiejar
import json
import os
from pathlib import Path
import re
import tempfile
from urllib import error, parse, request


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise error.HTTPError(req.full_url, code, 'Redirect rejected', headers, fp)


def site_origin(raw):
    if any(c.isspace() for c in raw) or '@' in raw:
        raise ValueError('Use the HTTPS site origin only.')
    value = parse.urlsplit(raw)
    if value.scheme != 'https' or not value.hostname or value.path not in ('', '/') or value.query or value.fragment:
        raise ValueError('Use the HTTPS site origin, without /gateway or /manage.')
    host = value.hostname.encode('idna').decode('ascii').lower()
    if ':' in host:
        host = '[' + host + ']'
    port = value.port
    return 'https://' + host + (':' + str(port) if port and port != 443 else '')


def save_session(path, origin, token):
    if not re.fullmatch(r'[A-Za-z0-9_-]{43}', token):
        raise ValueError('Unexpected session format.')
    path = Path(path).expanduser().absolute()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError('Session destination must not be a symbolic link.')
    fd, temporary = tempfile.mkstemp(prefix='.mcp-session-', dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as output:
            json.dump({'version': 1, 'origin': origin, 'session': token}, output)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url', help='HTTPS site origin only')
    parser.add_argument('--session-file', default=str(Path(__file__).absolute().parents[1] / 'secrets/mcp-session.json'))
    args = parser.parse_args()
    try:
        origin = site_origin(args.url)
    except ValueError as failure:
        raise SystemExit(str(failure))
    username = input('Admin username: ').strip()
    password = getpass('Password (hidden): ')
    jar = http.cookiejar.CookieJar()
    opener = request.build_opener(NoRedirect(), request.HTTPCookieProcessor(jar))
    req = request.Request(origin + '/portal/api/login',
        data=json.dumps({'username': username, 'password': password}).encode(),
        headers={'Content-Type': 'application/json', 'X-Portal-Request': '1'})
    try:
        with opener.open(req, timeout=15) as response:
            raw = response.read(16385)
            if len(raw) > 16384:
                raise ValueError('Login response too large')
            result = json.loads(raw)
        if result.get('user', {}).get('role') != 'admin':
            raise ValueError('Administrator access required')
        token = next(cookie.value for cookie in jar if cookie.name == 'meter_session' and cookie.path == '/')
        save_session(args.session_file, origin, token)
    except error.HTTPError as failure:
        raise SystemExit('Login failed: HTTP ' + str(failure.code) + '. Redirects are not followed.')
    except (OSError, ValueError, KeyError, StopIteration):
        raise SystemExit('MCP login could not be saved. Check the origin, administrator role and private destination.')
    print('MCP session saved privately. No password or token was printed. Re-login after expiry; revoke sessions in the dashboard to disconnect access.')


if __name__ == '__main__':
    main()
