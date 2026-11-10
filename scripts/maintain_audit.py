#!/usr/bin/env python3
"""Prune only committed Kafka history older than 24h; retain database audit records."""
import fcntl
import json
import time
import monitor_operations as monitor
import blue_green as deploy


def main(pressure=False):
    manifest_file=deploy.STATE/'release.json'
    if not manifest_file.exists():
        print('Audit maintenance skipped: deployment is not configured.');return
    with (deploy.STATE/'deploy.lock').open('w') as deployment_lock, (deploy.STATE/'audit-maintenance.lock').open('w') as lock:
        fcntl.flock(deployment_lock,fcntl.LOCK_EX)
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return
        manifest=json.loads(manifest_file.read_text())
        if manifest.get('phase')!='stable' or manifest.get('audit',{}).get('transport')!='kafka':
            print('Audit maintenance skipped: stable Kafka deployment is required.');return
        env=deploy.release_env(manifest)
        deploy.verify_shared_audit(manifest,env)
        if pressure:
            active=manifest['active']
            if active not in ('blue','green'):raise RuntimeError('Unknown serving slot')
            state=deploy.api(active)
            if state.get('auditReady') is not False:raise RuntimeError('Capacity rejection no longer active')
            selector='{reason="LOG_BUDGET",job="subscription-meter",instance="app-'+active+':8180"}'
            if monitor.query('max(meter_audit_guard_reason'+selector+' and on(instance,job) (up{job="subscription-meter"} == 1))',time.time())!=1:
                raise RuntimeError('Capacity pressure not verified')
        deploy.compose(['run','--rm','--no-deps','audit-group-init','--prune-committed-now' if pressure else '--prune-committed'],env,True)
        print('Committed Kafka history maintenance completed; database capture retained.')


if __name__=='__main__':main()
