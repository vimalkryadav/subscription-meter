import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import sys
import unittest
from unittest.mock import patch
from unittest.mock import MagicMock

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
            backup.capacity(backup.MAX_DATABASE + 1)
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

    def test_coupled_policy_rejects_insufficient_store_and_invalid_values(self):
        original = dict(backup.BUDGET)
        path = self.store / 'policy.json'
        for changes in ({'max_store_bytes': original['max_archive_bytes'] * 8}, {'max_database_bytes': 1}, {'restore_cpus': True}, {'warning_percent': 100}):
            path.write_text(json.dumps({**original, **changes}))
            with self.assertRaises(ValueError): backup.backup_policy.load(path)

    def test_inventory_versions_preserve_old_archives_and_cover_recovery_state(self):
        legacy = backup.inventory_sql(1); current = backup.inventory_sql(2)
        self.assertNotIn('audit_operations', legacy)
        for table in ('audit_operations','audit_payload_parts','control_schema_history','accounting_price_versions','admin_action_log'):
            self.assertIn(table, current)
        self.assertNotIn('portal_refresh_tokens', current)
        for table in ('portal_refresh_families','portal_access_tokens','portal_refresh_tokens'):
            self.assertIn(table, backup.inventory_sql(3))
        with self.assertRaises(ValueError): backup.inventory_sql(4)
        archive, meta = self.archive()
        with patch.object(backup,'output',return_value='owned-restore'),patch.object(backup,'docker'), \
                patch.object(backup,'sql',return_value='1'),patch.object(backup,'inventory',return_value=meta['inventory']) as inv:
            backup.restore(archive,meta)
        inv.assert_called_once_with('owned-restore',1)

    def test_snapshot_selects_pre_or_post_migration_inventory_inside_same_transaction(self):
        for version in (2, 3):
            process = MagicMock()
            with patch.object(backup.subprocess, 'Popen', return_value=process), \
                    patch.object(backup, 'read_line', side_effect=['00000001-00000002-1', str(version), '{"requests":12}']):
                with backup.snapshot('owned-source') as (identifier, expected, actual):
                    self.assertEqual(actual, version)
                    self.assertEqual(expected, {'requests': 12})
                    writes = [call.args[0] for call in process.stdin.write.call_args_list]
                    self.assertIn('REPEATABLE READ READ ONLY', writes[0])
                    self.assertIn("to_regclass('portal_refresh_families')", writes[1])
                    self.assertEqual('portal_refresh_tokens' in writes[2], version == 3)
            process.stdin.close.assert_called_once()
            process.wait.assert_called_once()

    def test_capacity_warns_early_and_reserves_pending_archive_without_pruning(self):
        archive, meta = self.archive()
        meta['createdAt'] = '2026-11-10T14:00:00+00:00'
        backup.write_json(archive.with_suffix('.json'),meta)
        with archive.open('r+b') as handle: handle.truncate(int(backup.MAX_ARCHIVE * .85))
        with patch.object(backup,'source',return_value=('source','image')),patch.object(backup,'sql',return_value='1000'), \
                patch.object(backup.shutil,'disk_usage',return_value=type('Disk',(),{'free':20*1024**3})()),patch.object(backup,'prune') as prune:
            result=backup.capacity_report()
        self.assertEqual(result['status'],'warning')
        self.assertEqual(result['rows'][0]['state'],'warning')
        store=next(row for row in result['rows'] if row['id']=='store')
        self.assertEqual(store['reserveBytes'],backup.MAX_ARCHIVE)
        self.assertEqual(store['remainingBytes'],backup.MAX_STORE-store['usedBytes']-backup.MAX_ARCHIVE)
        prune.assert_not_called()
        self.assertNotIn(str(self.store),json.dumps(result))

    def test_capacity_failure_does_not_export_dependency_error(self):
        with patch.object(backup,'source',side_effect=RuntimeError('private credential')):
            result=backup.capacity_report()
        self.assertEqual(result['status'],'unknown')
        self.assertEqual(result['rows'],[])
        self.assertNotIn('credential',str(result))


if __name__ == '__main__':
    unittest.main()
