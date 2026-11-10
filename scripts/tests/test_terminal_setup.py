import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('terminal_setup',Path(__file__).resolve().parents[2]/'frontend/setup/setup-claude.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class SetupTest(unittest.TestCase):
    def test_persists_reuses_and_removes_without_touching_personal_login(self):
        with tempfile.TemporaryDirectory() as temp:
            home=Path(temp).resolve();personal=home/'.claude';personal.mkdir();(personal/'settings.json').write_text('{"personal":true}')
            key='sm_'+'a'*32
            launcher=m.configure(home,'https://example.com',key)
            profile=home/'.config/control-usage/claude/settings.json'
            self.assertEqual(stat.S_IMODE(profile.stat().st_mode),0o600)
            config=json.loads(profile.read_text());config['theme']='dark';profile.write_text(json.dumps(config))
            m.configure(home,'https://new.example.com','sm_'+'b'*32)
            config=json.loads(profile.read_text());self.assertEqual(config['theme'],'dark');self.assertEqual(config['env']['ANTHROPIC_BASE_URL'],'https://new.example.com/gateway')
            self.assertNotIn(key,launcher.read_text())
            fake=home/'claude';fake.write_text('#!/bin/sh\n[ -z "$ANTHROPIC_API_KEY" ] && [ -z "$ANTHROPIC_AUTH_TOKEN" ] && [ -f "$CLAUDE_CONFIG_DIR/settings.json" ] && [ "$1" = "--version" ]\n');fake.chmod(0o700)
            for _ in range(2):subprocess.run([str(launcher),'--version'],env={**os.environ,'PATH':str(home)+':'+os.environ['PATH'],'ANTHROPIC_API_KEY':'conflicting','ANTHROPIC_AUTH_TOKEN':'conflicting'},check=True)
            m.remove(home);self.assertFalse(launcher.exists());self.assertNotIn('ANTHROPIC_AUTH_TOKEN',json.loads(profile.read_text())['env'])
            self.assertEqual((personal/'settings.json').read_text(),'{"personal":true}')
    def test_rejects_unowned_launcher_and_symlink_without_writing_key(self):
        with tempfile.TemporaryDirectory() as temp:
            home=Path(temp).resolve();binary=home/'.local/bin';binary.mkdir(parents=True);(binary/'claude-work').write_text('user script')
            with self.assertRaises(ValueError):m.configure(home,'https://example.com','sm_'+'a'*32)
            self.assertFalse((home/'.config/control-usage/claude/settings.json').exists())
            (binary/'claude-work').unlink();(home/'.config/control-usage/claude/settings.json').symlink_to(home/'target')
            with self.assertRaises(ValueError):m.configure(home,'https://example.com','sm_'+'a'*32)
            self.assertFalse((home/'target').exists())
    def test_rejects_invalid_origin_and_upstream_credentials(self):
        for url in ['http://example.com','https://user:pass@example.com','https://example.com/gateway','https://example.com?x=1']:
            with self.assertRaises(ValueError):m.origin(url)
        with self.assertRaises(ValueError):m.configure(Path('/unused'),'https://example.com','upstream-token')
