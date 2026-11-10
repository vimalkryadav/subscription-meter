import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('terminal_setup',Path(__file__).resolve().parents[2]/'frontend/setup/setup-claude.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

@unittest.skipUnless(os.name == 'posix', 'Optional shell launcher is Unix-only')
class SetupTest(unittest.TestCase):
    def test_persists_reuses_and_removes_without_touching_personal_login(self):
        with tempfile.TemporaryDirectory() as temp:
            home=Path(temp).resolve();personal=home/'.claude';personal.mkdir();(personal/'settings.json').write_text('{"personal":true}')
            key='sm_'+'a'*32
            launcher=m.configure_profile(home,'https://example.com',key)
            profile=home/'.config/control-usage/claude/settings.json'
            self.assertEqual(stat.S_IMODE(profile.stat().st_mode),0o600)
            config=json.loads(profile.read_text());config['theme']='dark';profile.write_text(json.dumps(config))
            m.configure_profile(home,'https://new.example.com','sm_'+'b'*32)
            config=json.loads(profile.read_text());self.assertEqual(config['theme'],'dark');self.assertEqual(config['env']['ANTHROPIC_BASE_URL'],'https://new.example.com/gateway')
            self.assertNotIn(key,launcher.read_text())
            fake=home/'claude';fake.write_text('#!/bin/sh\n[ -z "$ANTHROPIC_API_KEY" ] && [ -z "$ANTHROPIC_AUTH_TOKEN" ] && [ -f "$CLAUDE_CONFIG_DIR/settings.json" ] && [ "$1" = "--version" ]\n');fake.chmod(0o700)
            for _ in range(2):subprocess.run([str(launcher),'--version'],env={**os.environ,'PATH':str(home)+':'+os.environ['PATH'],'ANTHROPIC_API_KEY':'conflicting','ANTHROPIC_AUTH_TOKEN':'conflicting'},check=True)
            m.remove_profile(home);self.assertFalse(launcher.exists());self.assertNotIn('ANTHROPIC_AUTH_TOKEN',json.loads(profile.read_text())['env'])
            self.assertEqual((personal/'settings.json').read_text(),'{"personal":true}')
    def test_rejects_unowned_launcher_and_symlink_without_writing_key(self):
        with tempfile.TemporaryDirectory() as temp:
            home=Path(temp).resolve();binary=home/'.local/bin';binary.mkdir(parents=True);(binary/'claude-work').write_text('user script')
            with self.assertRaises(ValueError):m.configure_profile(home,'https://example.com','sm_'+'a'*32)
            self.assertFalse((home/'.config/control-usage/claude/settings.json').exists())
            (binary/'claude-work').unlink();(home/'.config/control-usage/claude/settings.json').symlink_to(home/'target')
            with self.assertRaises(ValueError):m.configure_profile(home,'https://example.com','sm_'+'a'*32)
            self.assertFalse((home/'target').exists())
    def test_rejects_invalid_origin_and_upstream_credentials(self):
        for url in ['http://example.com','https://user:pass@example.com','https://example.com/gateway','https://example.com?x=1']:
            with self.assertRaises(ValueError):m.origin(url)
        with self.assertRaises(ValueError):m.configure_profile(Path('/unused'),'https://example.com','upstream-token')

class DirectSetupTest(unittest.TestCase):
    key = 'sm_' + 'a' * 32

    def test_default_merges_rotates_and_undo_restores_first_values(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp).resolve()
            folder = home / '.claude'; folder.mkdir()
            settings = folder / 'settings.json'
            original = {'permissions': {'allow': ['Read']}, 'hooks': {'Stop': []},
                        'env': {'ANTHROPIC_API_KEY': 'prior-private-key', 'OTHER': 'keep',
                                'CLAUDE_CODE_USE_BEDROCK': '1'}}
            settings.write_text(json.dumps(original))
            credential = folder / '.credentials.json'; credential.write_text('do not read or alter')
            m.configure(home, 'https://example.com', self.key)
            backup = home / '.config/control-usage/direct-setup-backup.json'
            self.assertEqual(json.loads(backup.read_text())['original'], original)
            for private in (settings, backup):
                if os.name == 'posix':
                    self.assertEqual(stat.S_IMODE(private.stat().st_mode), 0o600)
                else:
                    self.assert_private_acl(private)
            data = json.loads(settings.read_text())
            self.assertEqual(data['hooks'], original['hooks'])
            self.assertEqual(data['permissions'], original['permissions'])
            self.assertEqual(data['env']['ANTHROPIC_API_KEY'], '')
            data['theme'] = 'dark'; settings.write_text(json.dumps(data))
            m.configure(home, 'https://new.example.com', 'sm_' + 'b' * 32)
            self.assertEqual(json.loads(backup.read_text())['original'], original)
            self.assertEqual(json.loads(settings.read_text())['env']['ANTHROPIC_BASE_URL'], 'https://new.example.com/gateway')
            self.assertEqual(m.remove(home), [])
            self.assertEqual(json.loads(settings.read_text()), {**original, 'theme': 'dark'})
            self.assertFalse(backup.exists())
            self.assertEqual(credential.read_text(), 'do not read or alter')
            self.assertFalse((home / '.local/bin/claude-work').exists())
            m.remove(home)  # idempotent

    def test_undo_preserves_later_managed_edits_and_unrelated_settings(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp).resolve()
            settings = m.configure(home, 'https://example.com', self.key)
            data = json.loads(settings.read_text())
            data['env']['ANTHROPIC_AUTH_TOKEN'] = 'user replacement'
            data['model'] = 'personal model'
            settings.write_text(json.dumps(data))
            self.assertEqual(m.remove(home), ['ANTHROPIC_AUTH_TOKEN'])
            self.assertEqual(json.loads(settings.read_text()), {'model': 'personal model', 'env': {'ANTHROPIC_AUTH_TOKEN': 'user replacement'}})

    def test_new_settings_undo_and_interrupted_reinstall(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp).resolve()
            settings = m.configure(home, 'https://example.com', self.key)
            original_atomic = m.atomic
            def interrupt(path, content, mode):
                if path == settings: raise OSError('simulated interruption')
                return original_atomic(path, content, mode)
            with patch.object(m, 'atomic', side_effect=interrupt):
                with self.assertRaises(OSError):
                    m.configure(home, 'https://new.example.com', 'sm_' + 'b' * 32)
            m.remove(home)
            self.assertEqual(json.loads(settings.read_text()), {})

    def test_unsafe_or_invalid_settings_are_not_replaced(self):
        for kind in (('malformed', 'invalid-env') if os.name == 'nt' else ('symlink', 'malformed', 'invalid-env', 'backup-link')):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                home = Path(temp).resolve(); folder = home / '.claude'; folder.mkdir()
                settings = folder / 'settings.json'
                if kind == 'symlink': settings.symlink_to(home / 'target')
                else: settings.write_text('{broken' if kind == 'malformed' else '{"env":[]}' if kind == 'invalid-env' else '{}')
                if kind == 'backup-link':
                    state = home / '.config/control-usage'; state.mkdir(parents=True)
                    (state / 'direct-setup-backup.json').symlink_to(home / 'target')
                before = settings.read_text() if settings.exists() else None
                with self.assertRaises(ValueError): m.configure(home, 'https://example.com', self.key)
                self.assertFalse((home / 'target').exists())
                if before is not None: self.assertEqual(settings.read_text(), before)

    def test_custom_config_directory_stops_before_prompt(self):
        result = subprocess.run([sys.executable, str(Path(m.__file__)), '--url', 'https://example.com'],
                                env={**os.environ, 'CLAUDE_CONFIG_DIR': '/other/profile'},
                                stdin=subprocess.DEVNULL, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('CLAUDE_CONFIG_DIR is set', result.stdout)
        self.assertNotIn('member key (hidden)', result.stderr)

    def assert_private_acl(self, path):
        script = r"""
$ErrorActionPreference = 'Stop'
$acl = Get-Acl -LiteralPath $env:CONTROL_USAGE_TEST_PATH
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
if (-not $acl.AreAccessRulesProtected) { throw 'inherited' }
$rules = @($acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]))
if ($rules.Count -ne 2) { throw 'rule count' }
foreach ($rule in $rules) {
 if ($rule.IdentityReference.Value -notin @($sid, 'S-1-5-18') -or $rule.AccessControlType -ne 'Allow' -or $rule.FileSystemRights -ne 'FullControl') { throw 'unsafe rule' }
}
"""
        subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
                       env={**os.environ, 'CONTROL_USAGE_TEST_PATH': str(path)}, check=True,
                       capture_output=True, timeout=30)

    def test_unicode_settings_and_bom(self):
        with tempfile.TemporaryDirectory(prefix='control usage ') as temp:
            home = Path(temp).resolve() / 'équipe'; home.mkdir()
            folder = home / '.claude'; folder.mkdir()
            settings = folder / 'settings.json'
            settings.write_text(json.dumps({'language': '日本語'}, ensure_ascii=False), encoding='utf-8-sig')
            m.configure(home, 'https://example.com', self.key)
            self.assertEqual(json.loads(settings.read_text(encoding='utf-8'))['language'], '日本語')
            m.remove(home)
            self.assertEqual(json.loads(settings.read_text(encoding='utf-8')), {'language': '日本語'})

    @unittest.skipUnless(os.name == 'nt', 'Native Windows junction check')
    def test_windows_junction_is_rejected_before_writing(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp).resolve(); target = home / 'target'; target.mkdir()
            link = home / '.claude'
            subprocess.run(['cmd.exe', '/c', 'mklink', '/J', str(link), str(target)], check=True, capture_output=True)
            try:
                with self.assertRaises(ValueError): m.configure(home, 'https://example.com', self.key)
                self.assertEqual(list(target.iterdir()), [])
            finally:
                link.rmdir()

    def test_concurrent_setup_retains_first_backup(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp).resolve(); folder = home / '.claude'; folder.mkdir()
            (folder / 'settings.json').write_text('{"model":"keep"}')
            code = "import importlib.util,os;from pathlib import Path;s=importlib.util.spec_from_file_location('setup',os.environ['SETUP_SCRIPT']);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.configure(Path(os.environ['SETUP_TEST_HOME']),os.environ['SETUP_TEST_URL'],'sm_'+'a'*32)"
            processes = []
            try:
                for origin in ('https://one.example.com', 'https://two.example.com'):
                    processes.append(subprocess.Popen([sys.executable, '-c', code], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                        env={**os.environ, 'SETUP_SCRIPT': m.__file__, 'SETUP_TEST_HOME': str(home), 'SETUP_TEST_URL': origin}))
                for process in processes:
                    output = process.communicate(timeout=120)
                    self.assertEqual(process.returncode, 0, output)
                state = json.loads((home / '.config/control-usage/direct-setup-backup.json').read_text())
                self.assertEqual(state['original'], {'model': 'keep'})
                m.remove(home)
                self.assertEqual(json.loads((folder / 'settings.json').read_text()), {'model': 'keep'})
            finally:
                for process in processes:
                    if process.poll() is None: process.kill(); process.communicate()

    @unittest.skipUnless(os.name == 'nt', 'Native Windows ACL diagnostics on an empty fixture')
    def test_windows_private_directory(self):
        from unittest.mock import patch
        original_run = subprocess.run
        def fixture_run(*args, **kwargs):
            kwargs.update(stderr=subprocess.PIPE, text=True)
            try: return original_run(*args, **kwargs)
            except subprocess.CalledProcessError as error:
                # This test only touches an empty temporary directory: no keys or settings.
                raise AssertionError(error.stderr) from None
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp).resolve() / 'private'
            with patch.object(m.subprocess, 'run', side_effect=fixture_run):
                m.secure_directory(folder)
            self.assert_private_acl(folder)
