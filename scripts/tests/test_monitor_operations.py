import importlib.util
import datetime as dt
import unittest
from unittest.mock import patch
from pathlib import Path

spec=importlib.util.spec_from_file_location('monitor_operations',Path(__file__).resolve().parents[1]/'monitor_operations.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class MonitorTests(unittest.TestCase):
    def test_alert_requires_three_consecutive_checks_and_recovers_once(self):
        checks={k:('ok',1,'') for k in m.CHECKS};checks['gateway']=('failed',0,'')
        previous={}
        for step in range(3):
            previous,events=m.report(checks,previous,1800000000+step*60)
            self.assertEqual(events, ['ALERT gateway'] if step==2 else [])
        checks['gateway']=('ok',1,'')
        previous,events=m.report(checks,previous,1800000180)
        self.assertEqual(events,['RECOVERED gateway']);self.assertEqual(previous['status'],'ok')
        self.assertEqual(m.report(checks,previous,1800000240)[1],[])

    def test_stale_previous_report_cannot_fake_sustained_failure(self):
        checks={k:('unknown',None,'') for k in m.CHECKS}
        prior,_=m.report(checks,{},1800000000)
        current,events=m.report(checks,prior,1800001000)
        self.assertEqual(events,[])
        self.assertTrue(all(c['consecutiveFailures']==1 for c in current['checks']))

    def test_failed_latest_backup_overrides_recent_success_and_future_is_unknown(self):
        now=1800000000
        data={'lastSuccessAt':dt.datetime.fromtimestamp(now-60,dt.timezone.utc).isoformat(),'lastAttemptState':'failed'}
        self.assertEqual(m.backup_check(data,False,now)[0],'failed')
        data['lastAttemptState']='ok';self.assertEqual(m.backup_check(data,False,now)[0],'ok')
        with self.assertRaises(ValueError):m.age_hours('2099-01-01T00:00:00Z',now)

    def test_running_backup_or_restore_requires_fresh_success_and_bounded_attempt(self):
        now=1800000000
        def stamp(age):return dt.datetime.fromtimestamp(now-age,dt.timezone.utc).isoformat()
        for restore in (False,True):
            success='lastRestoreAt' if restore else 'lastSuccessAt'
            state='lastRestoreState' if restore else 'lastAttemptState'
            attempt='lastRestoreAttemptAt' if restore else 'lastAttemptAt'
            data={success:stamp(3600),state:'running',attempt:stamp(1199)}
            self.assertEqual(m.backup_check(data,restore,now)[0],'ok')
            data[attempt]=stamp(1201)
            self.assertEqual(m.backup_check(data,restore,now)[0],'failed')
            data[attempt]=stamp(60);data[success]=stamp((193 if restore else 31)*3600)
            self.assertEqual(m.backup_check(data,restore,now)[0],'failed')
            data[success]=stamp(60);data[state]='failed'
            self.assertEqual(m.backup_check(data,restore,now)[0],'failed')
            data[state]='running';del data[attempt]
            self.assertEqual(m.backup_check(data,restore,now)[0],'failed')
            del data[success]
            with self.assertRaises(ValueError):m.backup_check(data,restore,now)
            # Collection converts missing successful history into unknown.
            with patch.object(m,'read_json',return_value=data),patch.object(m,'query',return_value=1):
                self.assertEqual(m.collect(now)['restore' if restore else 'backup'][0],'unknown')

    def test_scrape_failure_does_not_expose_dependency_error(self):
        with patch.object(m,'query',side_effect=RuntimeError('secret-token-and-capture')),patch.object(m,'read_json',return_value={}):
            result=m.collect(1800000000)
        report,_=m.report(result,{},1800000000)
        self.assertEqual(result['worker_ready'][0],'unknown')
        self.assertNotIn('secret-token',str(report))
        self.assertNotIn('capture',str(report))

    def test_missing_or_failed_active_scrape_cannot_report_zero_auth_errors(self):
        for unavailable in (False, True):
            queries=[]
            def query(expression, now):
                queries.append(expression)
                if expression.startswith('max(up'):
                    if unavailable:raise TimeoutError('dependency failure')
                    return 0
                return 0
            with patch.object(m,'query',side_effect=query),patch.object(m,'read_json',return_value={'active':'green','phase':'stable'}):
                result=m.collect(1800000000)
            self.assertEqual(result['upstream_auth'],('unknown',None,'responses / 10 min'))
            self.assertFalse(any('meter_requests_total' in q for q in queries))

    def test_active_slot_selector_excludes_stopped_standby_and_rolling_allows_both(self):
        queries=[]
        def query(expression,now):queries.append(expression);return 1
        for phase in ('stable','switching'):
            queries.clear()
            with patch.object(m,'query',side_effect=query),patch.object(m,'read_json',return_value={'active':'green','phase':phase}):m.collect(1800000000)
            scrape=next(q for q in queries if q.startswith('max(up'))
            self.assertEqual('instance="app-green:8180"' in scrape,phase=='stable')

    def test_capacity_warning_and_stale_observations_need_attention(self):
        now=1800000000
        data={'generatedAt':dt.datetime.fromtimestamp(now,dt.timezone.utc).isoformat(),'status':'warning'}
        self.assertEqual(m.capacity_check(data,now)[0],'failed')
        data['status']='ok';self.assertEqual(m.capacity_check(data,now)[0],'ok')
        self.assertEqual(m.capacity_check(data,now+181)[0],'unknown')
        data['status']='unknown';self.assertEqual(m.capacity_check(data,now)[0],'unknown')

if __name__=='__main__':unittest.main()
