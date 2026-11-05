import copy
import unittest
from verify_live_cutover import verify


def receipt():
    rows = []
    for cohort, release, start in [('before', 'a'*40, 0), ('after', 'b'*40, 10000)]:
        for index in range(10):
            rows.append(dict(cohort=cohort, index=index, startedMs=start, firstMs=start+100,
                             lastMs=start+35100, finishedMs=start+35110, status=200, release=release,
                             events=71, complete=True, serverSpanMs=35000, deliverySpanMs=35000,
                             protocol='h2', error=None))
    return dict(schemaVersion=1, before='a'*40, after='b'*40, state='complete', cutoverMs=10000, rows=rows)


class LiveCutoverReceiptTest(unittest.TestCase):
    def test_two_complete_overlapping_cohorts_pass(self):
        result = verify(receipt())
        self.assertEqual(result['streams'], 20)
        self.assertEqual(result['events'], 1420)
        self.assertEqual(result['firstEventP95Ms'], 100)

    def test_failed_truncated_misrouted_and_nonoverlapping_streams_fail(self):
        mutations = [
            lambda r: r.update(state='failed'),
            lambda r: r.update(cutoverMs=None),
            lambda r: r.update(cutoverMs=40000),
            lambda r: r.update(after=r['before']),
            lambda r: r['rows'].pop(),
            lambda r: r['rows'][0].update(index=1),
            lambda r: r['rows'][0].update(status=429),
            lambda r: r['rows'][0].update(release='b'*40),
            lambda r: r['rows'][0].update(events=70),
            lambda r: r['rows'][0].update(error='transport_failure'),
            lambda r: r['rows'][0].update(firstMs=float('nan')),
            lambda r: r['rows'][0].update(firstMs=True),
            lambda r: r['rows'][0].update(firstMs=35000, deliverySpanMs=100),
            lambda r: r['rows'][10].update(startedMs=5000),
            lambda r: r['rows'][10].update(firstMs=36000, lastMs=71000, finishedMs=71001, startedMs=35900),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                data=copy.deepcopy(receipt());mutation(data)
                with self.assertRaises(ValueError): verify(data)
