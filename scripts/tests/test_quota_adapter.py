import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'collect_claude_statusline.py'


class QuotaAdapterTests(unittest.TestCase):
    def run_adapter(self, destination, payload):
        return subprocess.run([sys.executable, str(SCRIPT), str(destination), 'primary'],
                              input=json.dumps(payload), text=True, capture_output=True, check=True)

    def test_retains_only_reported_quota_and_private_file_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'quota.json'
            payload = {'credential': 'synthetic-secret-must-not-survive',
                       'context_window': {'used_percentage': 99},
                       'rate_limits': {'five_hour': {'used_percentage': 3, 'resets_at': 1900000000}}}
            result = self.run_adapter(target, payload)
            report = json.loads(target.read_text())
            self.assertEqual(report['rate_limits'], {'five_hour': {'used_percentage': 3, 'resets_at': 1900000000}})
            self.assertNotIn('synthetic-secret', target.read_text() + result.stdout + result.stderr)
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)

    def test_missing_or_malformed_windows_do_not_refresh_old_observation(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'quota.json'
            target.write_text('existing observation')
            for payload in [None, [], {'rate_limits': None}, {'rate_limits': {'five_hour': None}},
                            {'rate_limits': {'five_hour': {'used_percentage': True, 'resets_at': 1}}},
                            {'context_window': {'used_percentage': 25}}]:
                result = self.run_adapter(target, payload)
                self.assertEqual(target.read_text(), 'existing observation')
                self.assertEqual(result.stdout + result.stderr, '')

    def test_fable_stays_separate_and_generic_overage_is_not_relabelled(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'quota.json'
            self.run_adapter(target, {'rate_limits': {
                'seven_day': {'used_percentage': 30, 'resets_at': 1900000000},
                'seven_day_fable': {'used_percentage': 80, 'resets_at': 1900003600},
                'seven_day_overage_included': {'used_percentage': 99, 'resets_at': 1900000000}}})
            windows = json.loads(target.read_text())['rate_limits']
            self.assertEqual(set(windows), {'seven_day', 'seven_day_fable'})
            self.assertEqual(windows['seven_day_fable']['used_percentage'], 80)
            self.assertNotEqual(windows['seven_day_fable']['resets_at'], windows['seven_day']['resets_at'])


if __name__ == '__main__':
    unittest.main()
