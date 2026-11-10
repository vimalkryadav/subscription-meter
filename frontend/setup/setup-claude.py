#!/usr/bin/env python3
"""One-time private Claude Code gateway profile for macOS/Linux. No provider login changes."""
import argparse
from getpass import getpass
import json
import os
from pathlib import Path
import re
import shlex
import stat
import tempfile
from urllib.parse import urlsplit

MARKER = '# control-usage managed launcher v1'

def secure_directory(path):
    # Refuse redirected paths rather than writing a credential through a symlink.
    for parent in [*reversed(path.parents), path]:
        if parent.is_symlink():
            raise ValueError('A setup directory is a symlink; use a regular home directory.')
    path.mkdir(parents=True, exist_ok=True)
    if path.stat().st_uid != os.getuid():
        raise ValueError('Setup directory must belong to your user.')
    path.chmod(0o700)

def regular(path):
    if path.is_symlink() or (path.exists() and (not path.is_file() or path.stat().st_uid != os.getuid())):
        raise ValueError('Refusing an unsafe existing setup file.')

def atomic(path, content, mode):
    regular(path)
    fd, name = tempfile.mkstemp(prefix='.control-usage-', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'w') as out:
            out.write(content); out.flush(); os.fsync(out.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name): os.unlink(name)

def origin(value):
    u = urlsplit(value)
    if u.scheme != 'https' or not u.hostname or u.username or u.password or u.query or u.fragment or u.path not in ('', '/'):
        raise ValueError('Use the HTTPS platform origin only, without /gateway or /manage.')
    if any(c.isspace() for c in value): raise ValueError('Invalid platform origin.')
    return 'https://' + u.netloc

def configure(home, url, token):
    url = origin(url)
    if not re.fullmatch(r'sm_[A-Za-z0-9_-]{16,200}', token):
        raise ValueError('Enter your raw control-usage member key (sm_...), not an upstream credential.')
    profile = home / '.config/control-usage/claude'
    binary_dir = home / '.local/bin'
    # Do not change permissions on a shared user's existing bin directory.
    for target in (profile, binary_dir):
        for parent in [*reversed(target.parents), target]:
            if parent.is_symlink(): raise ValueError('Refusing a symlink in a setup path.')
    secure_directory(profile)
    binary_dir.mkdir(parents=True, exist_ok=True)
    if binary_dir.stat().st_uid != os.getuid(): raise ValueError('Launcher directory must belong to your user.')
    settings = profile / 'settings.json'; launcher = binary_dir / 'claude-work'
    regular(settings); regular(launcher)
    if launcher.exists() and MARKER not in launcher.read_text():
        raise ValueError('claude-work already exists and was not created by this installer.')
    data = json.loads(settings.read_text()) if settings.exists() else {}
    if not isinstance(data, dict) or not isinstance(data.get('env', {}), dict):
        raise ValueError('Existing profile settings are invalid; no settings replaced.')
    # Explicitly clear conflicting authentication and alternate provider modes in this profile only.
    env = data.setdefault('env', {})
    for name in ('ANTHROPIC_API_KEY','CLAUDE_CODE_OAUTH_TOKEN','CLAUDE_CODE_USE_BEDROCK','CLAUDE_CODE_USE_VERTEX','CLAUDE_CODE_USE_FOUNDRY'):
        env[name] = ''
    env.update(ANTHROPIC_BASE_URL=url+'/gateway', ANTHROPIC_AUTH_TOKEN=token)
    wrapper = '#!/bin/sh\n'+MARKER+'\n'
    wrapper += 'unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN ANTHROPIC_BASE_URL CLAUDE_CODE_OAUTH_TOKEN CLAUDE_CODE_OAUTH_REFRESH_TOKEN CLAUDE_CODE_USE_BEDROCK CLAUDE_CODE_USE_VERTEX CLAUDE_CODE_USE_FOUNDRY\n'
    wrapper += 'export CLAUDE_CONFIG_DIR='+shlex.quote(str(profile))+'\n'
    wrapper += 'exec claude "$@"\n'
    atomic(settings, json.dumps(data, indent=2)+'\n', 0o600)
    atomic(launcher, wrapper, 0o700)
    return launcher

def remove(home):
    profile = home / '.config/control-usage/claude'
    launcher = home / '.local/bin/claude-work'
    for path in (profile, launcher.parent):
        for parent in [*reversed(path.parents),path]:
            if parent.is_symlink(): raise ValueError('Refusing a symlink in setup paths.')
    regular(launcher)
    if launcher.exists() and MARKER not in launcher.read_text(): raise ValueError('Unrecognized launcher; nothing removed.')
    settings = profile / 'settings.json';regular(settings)
    if settings.exists():
        data = json.loads(settings.read_text())
        if not isinstance(data,dict) or not isinstance(data.get('env',{}),dict): raise ValueError('Invalid settings; remove the private token manually.')
        for name in ('ANTHROPIC_BASE_URL','ANTHROPIC_AUTH_TOKEN'):
            data.get('env',{}).pop(name,None)
        atomic(settings,json.dumps(data,indent=2)+'\n',0o600)
    if launcher.exists(): launcher.unlink()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',help='HTTPS platform origin (no paths)')
    parser.add_argument('--remove',action='store_true',help='Remove saved gateway credential and owned launcher; keep conversation history')
    args=parser.parse_args()
    if os.name != 'posix': parser.error('This installer supports macOS and Linux.')
    try:
        if args.remove:
            remove(Path.home());print('Saved gateway credential and launcher removed. History preserved.');return
        if not args.url: parser.error('--url is required')
        url=origin(args.url)
        token=getpass('Platform member key (hidden): ').strip()
        launcher=configure(Path.home(),url,token)
        print('Setup saved privately. In any terminal run: '+str(launcher))
        print('Personal Claude login and shell startup files were not changed.')
        print('Use /status to check the gateway. Re-run setup after key rotation or a tunnel URL change; restart running sessions.')
    except (ValueError,OSError) as error:
        # JSON errors can contain private source snippets; never echo file content or key values.
        print('Setup failed: '+(str(error) if isinstance(error,ValueError) and not isinstance(error,json.JSONDecodeError) else 'Could not safely read or write setup files.'))
        raise SystemExit(1)

if __name__=='__main__':main()
