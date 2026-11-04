#!/usr/bin/env python3
"""Prune only committed Kafka history older than 24h; retain database audit records."""
import fcntl
import json
import blue_green as deploy


def main():
    manifest_file=deploy.STATE/'release.json'
    if not manifest_file.exists():
        print('Audit maintenance skipped: deployment is not configured.');return
    with (deploy.STATE/'audit-maintenance.lock').open('w') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return
        manifest=json.loads(manifest_file.read_text())
        if manifest.get('phase')!='stable' or manifest.get('audit',{}).get('transport')!='kafka':
            print('Audit maintenance skipped: stable Kafka deployment is required.');return
        env=deploy.release_env(manifest)
        deploy.verify_shared_audit(manifest,env)
        deploy.compose(['run','--rm','--no-deps','audit-group-init','--prune-committed'],env,True)
        print('Committed Kafka history maintenance completed; database capture retained.')


if __name__=='__main__':main()
