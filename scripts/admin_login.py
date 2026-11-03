"""Sign in with the owner's password for operator scripts; never print credentials."""
import argparse
from getpass import getpass
import http.cookiejar
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('url')
base = parser.parse_args().url.rstrip('/')
parsed = urllib.parse.urlsplit(base)
assert parsed.scheme == 'https' and not parsed.path and not parsed.username and not parsed.query and not parsed.fragment
username = input('Admin username: ').strip()
password = getpass('Password (hidden): ')
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
req = urllib.request.Request(base + '/portal/api/login',
    data=json.dumps({'username': username, 'password': password}).encode(),
    headers={'Content-Type': 'application/json', 'X-Portal-Request': '1'})
try:
    with opener.open(req, timeout=30) as response:
        data = json.load(response)
except urllib.error.HTTPError as error:
    raise SystemExit('Login failed: HTTP ' + str(error.code))
if data['user']['role'] != 'admin':
    raise SystemExit('This account does not have administrator access.')
token = next(c.value for c in jar if c.name == 'meter_session' and c.path == '/')
path = Path(__file__).resolve().parents[1] / 'secrets/admin-session'
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, 'w') as out:
    out.write(token + '\n')
path.chmod(0o600)
print('Admin session saved privately for acceptance scripts. It expires after inactivity or logout.')
