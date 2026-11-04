import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import sys
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('backup_database', Path(__file__).parents[1] / 'backup_database.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Path(self.temp.name)
        self.patches = [patch.object(backup, 'STORE', self.store),
                        patch.object(backup, 'REPORT', self.store / 'report/status.json'),
                        patch.object(backup, 'docker_storage_free', return_value=20 * 1024**3)]
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in self.patches:
            item.stop()
        self.temp.cleanup()

    def archive(self, day=1, verified=False):
        archive = self.store / ('backup-202611%02dT010000Z-1234abcd.dump' % day)
        archive.write_bytes(b'PGDMPfixture')
        meta = {'image': 'sha256:' + 'a' * 64, 'sha256': backup.digest(archive),
                'inventory': {'requests': 12, 'adminCount': 1}}
        if verified:
            meta['verifiedAt'] = '2026-11-01T02:00:00+00:00'
        backup.write_json(archive.with_suffix('.json'), meta)
        return archive, meta

    def test_prune_preserves_last_verified_and_unrelated_files(self):
        protected, _ = self.archive(1, True)
        removed, _ = self.archive(2)
        for day in range(3, 10):
            self.archive(day)
        unrelated = self.store / 'operator-backup.dump'
        unrelated.write_text('operator owned')
        backup.prune()
        self.assertTrue(protected.exists())
        self.assertFalse(removed.exists())
        self.assertTrue(unrelated.exists())
        self.assertEqual(8, len(backup.completed_archives()))

    def test_checksum_failure_never_starts_restore(self):
        archive, meta = self.archive()
        archive.write_bytes(b'corrupted')
        with patch.object(backup, 'docker') as docker:
            with self.assertRaisesRegex(RuntimeError, 'checksum'):
                backup.restore(archive, meta)
            docker.assert_not_called()

    def test_restore_is_network_isolated_and_cleans_up_on_inventory_failure(self):
        archive, meta = self.archive()
        commands = []
        def command(args, **kwargs):
            commands.append(args)
            return subprocess.CompletedProcess(args, 0)
        def output(args, **kwargs):
            commands.append(args)
            return 'test-container'
        with patch.object(backup, 'output', side_effect=output), patch.object(backup, 'docker', side_effect=command), \
                patch.object(backup, 'sql', return_value='1'), patch.object(backup, 'inventory', return_value={'requests': 0}), \
                patch.object(backup.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 10 * 1024**3})()):
            with self.assertRaisesRegex(RuntimeError, 'inventory'):
                backup.restore(archive, meta)
        run = commands[0]
        self.assertEqual('none', run[run.index('--network') + 1])
        self.assertNotIn('-p', run)
        self.assertNotIn('--volumes-from', run)
        self.assertTrue(any('dst=/backup.dump,readonly' in part for part in run))
        self.assertEqual(['rm', '-f', '-v', 'test-container'], commands[-1])
        self.assertFalse(backup.REPORT.exists())

    def test_success_records_verification_only_after_matching_inventory(self):
        archive, meta = self.archive()
        with patch.object(backup, 'output', return_value='test-container'), patch.object(backup, 'docker'), \
                patch.object(backup, 'sql', return_value='1'), patch.object(backup, 'inventory', return_value=meta['inventory']), \
                patch.object(backup.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 10 * 1024**3})()):
            backup.restore(archive, meta)
        self.assertEqual('ok', json.loads(backup.REPORT.read_text())['lastRestoreState'])
        self.assertIn('verifiedAt', json.loads(archive.with_suffix('.json').read_text()))
        self.assertEqual(0o600, archive.with_suffix('.json').stat().st_mode & 0o777)

    def test_source_requires_exact_project_database(self):
        with patch.object(backup, 'output', return_value='abc123\ndef456'):
            with self.assertRaisesRegex(RuntimeError, 'Exactly one'):
                backup.source()

    def test_capacity_fails_closed_before_dump(self):
        with self.assertRaisesRegex(RuntimeError, 'budget'):
            backup.capacity(3 * 1024**3)
        with patch.object(backup.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 1024})()):
            with self.assertRaisesRegex(RuntimeError, 'free space'):
                backup.capacity(1024)

    def test_writer_bounds_bytes_even_when_child_ignores_file_rlimit(self):
        target = self.store / 'bounded.dump'
        with target.open('wb') as handle:
            with self.assertRaisesRegex(RuntimeError, 'size limit'):
                backup.bounded_output([sys.executable, '-c', 'import os; os.write(1,b"x"*200000)'], handle, 1024, 5)
        self.assertLessEqual(target.stat().st_size, 1024)

    def test_stalled_writer_has_deadline(self):
        with (self.store / 'stalled.dump').open('wb') as handle:
            with self.assertRaises(TimeoutError):
                backup.bounded_output([sys.executable, '-c', 'import time; time.sleep(10)'], handle, 1024, .1)

    def test_restore_rejects_low_docker_storage_even_with_backup_space(self):
        archive, meta = self.archive()
        with patch.object(backup, 'docker_storage_free', return_value=1024), \
                patch.object(backup.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 20 * 1024**3})()), \
                patch.object(backup, 'docker') as docker:
            with self.assertRaisesRegex(RuntimeError, 'Docker restore space'):
                backup.restore(archive, meta)
            docker.assert_not_called()

    def test_new_verified_archive_releases_previously_protected_copy(self):
        old, _ = self.archive(1, True)
        for day in range(2, 9):
            self.archive(day)
        backup.prune()
        self.assertTrue(old.exists())
        latest, meta = self.archive(8, True)
        backup.prune()
        self.assertFalse(old.exists())
        self.assertTrue(latest.exists())
        self.assertEqual(7, len(backup.completed_archives()))


if __name__ == '__main__':
    unittest.main()
