import importlib.util
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location('reclaim', Path(__file__).resolve().parents[1] / 'reclaim_capture_space.py')
reclaim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reclaim)
GIB = 1024**3


class ReclaimTest(unittest.TestCase):
    def test_only_fully_converted_chunks_with_room_are_rewritten(self):
        self.assertEqual('conversion not finished', reclaim.decide(100, 1, GIB, 50 * GIB))
        self.assertEqual('empty', reclaim.decide(0, 0, GIB, 50 * GIB))
        self.assertEqual('not enough free space for a rewrite', reclaim.decide(100, 0, 3 * GIB, 7 * GIB))
        self.assertIsNone(reclaim.decide(100, 0, 3 * GIB, 9 * GIB))

    def test_chunk_names_are_strict(self):
        self.assertTrue(reclaim.CHUNK.fullmatch('_timescaledb_internal._hyper_2_90_chunk'))
        self.assertFalse(reclaim.CHUNK.fullmatch('_timescaledb_internal._hyper_2_90_chunk; DROP TABLE x'))

    def test_row_count_change_stops_the_run(self):
        from unittest.mock import patch
        calls = []
        counts = iter([(10, 0, GIB), (9, 0, GIB // 10)])
        with patch.object(reclaim.db, 'source', return_value=('c', 'i')), \
                patch.object(reclaim.db, 'docker_storage_free', return_value=100 * GIB), \
                patch.object(reclaim, 'chunks', return_value=['_timescaledb_internal._hyper_2_1_chunk']), \
                patch.object(reclaim, 'stats', side_effect=lambda container, chunk: next(counts)), \
                patch.object(reclaim.db, 'sql', side_effect=lambda container, query: calls.append(query) or ''), \
                patch.object(sys, 'argv', ['reclaim', '--apply']):
            with self.assertRaisesRegex(RuntimeError, 'Row count changed'):
                reclaim.main()
        self.assertTrue(any('VACUUM (FULL, ANALYZE)' in q for q in calls))

if __name__ == '__main__':
    unittest.main()
