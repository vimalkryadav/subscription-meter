#!/usr/bin/env python3
"""Persistent Claude Code gateway settings for macOS, Linux and Windows, with undo."""
import argparse
from contextlib import contextmanager
from getpass import getpass
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import tempfile
from urllib.parse import urlsplit

if os.name == 'nt':
    import msvcrt
else:
    import fcntl

MARKER = '# control-usage managed launcher v1'

def redirected(path):
    try:
        info = path.lstat()
        return stat.S_ISLNK(info.st_mode) or bool(getattr(info, 'st_file_attributes', 0) & 0x400)
    except FileNotFoundError:
        return False

def windows_access(path, private=False):
    # Pass paths as data, never interpolate them into PowerShell source. No key
    # is passed to the child process. Fail closed on unsupported ACL filesystems.
    script = r'''
$ErrorActionPreference = 'Stop'
$path = $env:CONTROL_USAGE_ACL_PATH
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$existing = Get-Acl -LiteralPath $path
if ($existing.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $sid.Value) { exit 21 }
if ($env:CONTROL_USAGE_ACL_PRIVATE -eq '1') {
    $directory = [System.IO.Directory]::Exists($path)
    if ($directory) {
        $acl = [System.Security.AccessControl.DirectorySecurity]::new()
        $inherit = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    } else {
        $acl = [System.Security.AccessControl.FileSecurity]::new()
        $inherit = [System.Security.AccessControl.InheritanceFlags]::None
    }
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($identity in @($sid, [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', $inherit, 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $path -AclObject $acl
    $check = Get-Acl -LiteralPath $path
    if (-not $check.AreAccessRulesProtected) { throw 'inheritance' }
    foreach ($rule in $check.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])) {
        if ($rule.IdentityReference.Value -notin @($sid.Value, 'S-1-5-18')) { throw 'unexpected access' }
    }
}
'''
    executable = Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
    try:
        subprocess.run([str(executable), '-NoProfile', '-NonInteractive', '-Command', script],
                       env={**os.environ, 'CONTROL_USAGE_ACL_PATH': str(path),
                            'CONTROL_USAGE_ACL_PRIVATE': '1' if private else '0'},
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       timeout=30, check=True)
    except subprocess.CalledProcessError as error:
        if error.returncode == 21:
            raise ValueError('Setup files must be owned by your Windows user. Run in a normal, non-administrator terminal.') from None
        raise ValueError('Could not apply private Windows file permissions (code '+str(error.returncode)+').') from None
    except (OSError, subprocess.SubprocessError):
        raise ValueError('Could not verify private Windows file permissions. Use a local directory owned by your user.') from None

def owned(path):
    if os.name == 'nt':
        windows_access(path)
    elif path.stat().st_uid != os.getuid():
        raise ValueError('Setup files must belong to your user.')

def secure_directory(path):
    # Refuse redirected paths rather than writing a credential through a symlink.
    for parent in [*reversed(path.parents), path]:
        if redirected(parent):
            raise ValueError('A setup directory is redirected; use a regular home directory.')
    path.mkdir(parents=True, exist_ok=True)
    if os.name == 'nt': windows_access(path, private=True)
    else:
        owned(path)
        path.chmod(0o700)

def regular(path):
    if redirected(path) or (path.exists() and not path.is_file()):
        raise ValueError('Refusing an unsafe existing setup file.')
    if path.exists(): owned(path)

def atomic(path, content, mode):
    regular(path)
    fd, name = tempfile.mkstemp(prefix='.control-usage-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as out:
            if os.name == 'nt': windows_access(Path(name), private=True)
            else: os.fchmod(out.fileno(), mode)
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

def configure_profile(home, url, token):
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

def remove_profile(home):
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

# These values override inherited authentication/provider choices only while installed.
MANAGED = ('ANTHROPIC_BASE_URL', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_API_KEY',
           'CLAUDE_CODE_OAUTH_TOKEN', 'CLAUDE_CODE_OAUTH_REFRESH_TOKEN',
           'CLAUDE_CODE_USE_BEDROCK', 'CLAUDE_CODE_USE_VERTEX', 'CLAUDE_CODE_USE_FOUNDRY')

def read_settings(path):
    regular(path)
    data = json.loads(path.read_text(encoding='utf-8-sig')) if path.exists() else {}
    if not isinstance(data, dict) or not isinstance(data.get('env', {}), dict):
        raise ValueError('Existing settings are invalid; no settings replaced.')
    return data

@contextmanager
def direct_files(home):
    folder = home / '.config/control-usage'
    secure_directory(folder)
    lock = folder / 'direct-setup.lock'
    regular(lock)
    fd = os.open(lock, os.O_CREAT | os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'r+b') as handle:
        if os.name == 'nt':
            windows_access(lock, private=True)
            if os.fstat(fd).st_size == 0:
                handle.write(b'\0'); handle.flush()
            handle.seek(0)
            msvcrt.locking(fd, msvcrt.LK_LOCK, 1)
        else:
            os.fchmod(fd, 0o600)
            fcntl.flock(handle, fcntl.LOCK_EX)
        try:
            profile = home / '.claude'
            secure_directory(profile)
            yield profile / 'settings.json', folder / 'direct-setup-backup.json'
        finally:
            if os.name == 'nt':
                handle.seek(0)
                msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)

def read_backup(path):
    regular(path)
    state = json.loads(path.read_text(encoding='utf-8'))
    if (not isinstance(state, dict) or state.get('version') != 1
            or not isinstance(state.get('original'), dict)
            or not isinstance(state['original'].get('env', {}), dict)
            or not isinstance(state.get('applied'), dict)
            or not isinstance(state.get('previous', {}), dict)
            or set(state['applied']) != set(MANAGED)
            or not all(isinstance(v, str) for v in state['applied'].values())):
        raise ValueError('Setup backup is invalid; no settings replaced.')
    return state

def configure(home, url, token):
    url = origin(url)
    if not re.fullmatch(r'sm_[A-Za-z0-9_-]{16,200}', token):
        raise ValueError('Enter your raw control-usage member key (sm_...), not an upstream credential.')
    with direct_files(home) as (settings, backup):
        data = read_settings(settings)
        # Retain the first pre-install settings across key rotations and URL changes.
        state = read_backup(backup) if backup.exists() else {
            'version': 1, 'original': json.loads(json.dumps(data)), 'applied': {}}
        applied = dict.fromkeys(MANAGED, '')
        applied.update(ANTHROPIC_BASE_URL=url+'/gateway', ANTHROPIC_AUTH_TOKEN=token)
        # Save recovery information before changing settings. Undo also handles an
        # interrupted reinstall by recognizing the previously installed values.
        state['previous'] = state['applied']
        state['applied'] = applied
        atomic(backup, json.dumps(state, indent=2)+'\n', 0o600)
        data.setdefault('env', {}).update(applied)
        atomic(settings, json.dumps(data, indent=2)+'\n', 0o600)
    return settings

def remove(home):
    with direct_files(home) as (settings, backup):
        if not backup.exists():
            regular(backup)
            return []
        state = read_backup(backup)
        data = read_settings(settings)
        original = state['original'].get('env', {})
        env = data.get('env', {})
        preserved = []
        for name in MANAGED:
            # Do not undo a user's subsequent edit to a managed value.
            if name not in env:
                continue
            if env[name] not in (state['applied'][name], state.get('previous', {}).get(name)):
                preserved.append(name)
                continue
            if name in original:
                env[name] = original[name]
            else:
                env.pop(name, None)
        if not env and 'env' not in state['original']:
            data.pop('env', None)
        if settings.exists():
            atomic(settings, json.dumps(data, indent=2)+'\n', 0o600)
        backup.unlink()
        return preserved

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', help='HTTPS platform origin (no paths)')
    parser.add_argument('--remove', action='store_true', help='Undo setup, preserving later unrelated settings and history')
    parser.add_argument('--separate-profile', action='store_true', help='Use the optional claude-work profile instead of ordinary claude')
    args = parser.parse_args()
    if os.name not in ('posix', 'nt'): parser.error('This installer supports macOS, Linux and Windows.')
    if os.name == 'nt' and args.separate_profile:
        parser.error('The optional separate-profile launcher requires macOS, Linux or WSL. Use default setup on native Windows.')
    try:
        home = Path.home().absolute()
        if not args.separate_profile and os.environ.get('CLAUDE_CONFIG_DIR'):
            raise ValueError('CLAUDE_CONFIG_DIR is set. Unset it to configure ordinary claude, or choose --separate-profile.')
        if args.remove:
            if args.separate_profile:
                remove_profile(home)
                print('Separate-profile gateway credential and launcher removed. History preserved.')
            else:
                preserved = remove(home)
                print('Setup undone. Later edits to managed settings were preserved: '+str(len(preserved))+'. History preserved.')
            return
        if not args.url: parser.error('--url is required')
        url = origin(args.url)
        token = getpass('Platform member key (hidden): ').strip()
        if args.separate_profile:
            launcher = configure_profile(home, url, token)
            print('Setup saved privately. In any terminal run: '+str(launcher))
        else:
            configure(home, url, token)
            print('Setup saved in ~/.claude/settings.json. In any new terminal run: claude')
            print('Private backup: ~/.config/control-usage/direct-setup-backup.json. Run this installer with --remove to undo.')
        print('Saved provider login and shell startup files were not changed.')
        print('Use /status to check the gateway. Re-run setup after key rotation or a tunnel URL change; restart running sessions.')
    except (ValueError, OSError) as error:
        # JSON errors can contain private source snippets; never echo file content or key values.
        print('Setup failed: '+(str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError) else 'Could not safely read or write setup files.'))
        raise SystemExit(1)

if __name__ == '__main__': main()
