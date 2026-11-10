#!/usr/bin/env python3
"""Hourly bounded health review. Only committed audit-history cleanup is an automatic repair."""
import datetime as dt
import fcntl
import json
import time
import blue_green as deploy
import monitor_operations as monitor
import maintain_audit


def decision(checks, log_budget):
    required=set(monitor.CHECKS)
    if set(checks)!=required or any(v[0]=='unknown' for v in checks.values()): return 'needs_attention'
    failed={key for key,value in checks.items() if value[0]!='ok'}
    if not failed: return 'healthy'
    if (failed <= {'gateway','audit_guard'} and 'audit_guard' in failed and log_budget
            and checks['worker_ready'][0]=='ok' and checks['worker_lag'][1]==0):
        return 'prune_committed'
    return 'needs_attention'


def main():
    monitor.REPORT_DIR.mkdir(exist_ok=True)
    with (monitor.REPORT_DIR/'recovery.lock').open('w') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return
        result={'schemaVersion':1,'generatedAt':dt.datetime.now(dt.timezone.utc).isoformat(),
                'status':'needs_attention','action':'none','failedChecks':[]}
        try:
            checks=monitor.collect(time.time())
            result['failedChecks']=[key for key,value in checks.items() if value[0]!='ok']
            manifest=monitor.read_json(deploy.STATE/'release.json')
            if manifest.get('phase')!='stable': raise ValueError('Deployment not stable')
            active=manifest['active']
            if active not in ('blue','green'):raise ValueError('Unknown slot')
            pressure=False
            if checks['audit_guard'][0]=='failed':
                pressure=monitor.query('max(meter_audit_guard_reason{reason="LOG_BUDGET",job="subscription-meter",instance="app-'+active+':8180"} and on(instance,job) (up{job="subscription-meter"} == 1))',time.time())==1
            action=decision(checks,pressure)
            if action=='prune_committed':
                # The helper rechecks live guard state under the deployment lock.
                maintain_audit.main(pressure=True)
                result['action']='prune_committed'
                for _ in range(6):
                    time.sleep(5)
                    checks=monitor.collect(time.time())
                    if all(value[0]=='ok' for value in checks.values()):break
                result['status']='recovered' if all(value[0]=='ok' for value in checks.values()) else 'needs_attention'
                result['failedChecks']=[key for key,value in checks.items() if value[0]!='ok']
            else:result['status']=action
        except Exception:
            result['status']='needs_attention' # No dependency messages, credentials or captured text in reports.
        result['generatedAt']=dt.datetime.now(dt.timezone.utc).isoformat()
        target=monitor.REPORT_DIR/'recovery.json'
        deploy.write_json(target,result)
        target.chmod(0o644) # Sanitized report only; gateway reads this mounted file.
        print('Hourly control-usage review: '+result['status']+'; action='+result['action'])
        # No restart loops, schema changes, credential rotation, arbitrary commands or untested code deployment.

if __name__=='__main__':main()
