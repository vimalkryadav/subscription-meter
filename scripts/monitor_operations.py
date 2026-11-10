#!/usr/bin/env python3
"""Bounded local-only operational snapshot. Logs fixed check IDs, never dependency bodies."""
import datetime as dt
import fcntl
import json
import math
import os
from pathlib import Path
import shutil
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPORT_DIR = ROOT / 'deploy/operations'
CHECKS = ('gateway', 'prometheus', 'worker_ready', 'worker_lag', 'audit_guard', 'upstream_auth', 'disk', 'backup', 'restore', 'backup_capacity')

def read_json(path):
    with path.open('rb') as stream:
        body = stream.read(32769)
    if len(body) > 32768: raise ValueError('oversize')
    value = json.loads(body)
    if not isinstance(value, dict): raise ValueError('object required')
    return value

def query(expression, now):
    url = 'http://127.0.0.1:9190/api/v1/query?' + urllib.parse.urlencode({'query': expression, 'time': now, 'timeout': '2s'})
    with urllib.request.urlopen(url, timeout=3) as response: body = response.read(32769)
    if len(body) > 32768: raise ValueError('oversize')
    data = json.loads(body)
    if data.get('status') != 'success' or data['data']['resultType'] != 'vector': raise ValueError('query failed')
    rows = data['data']['result']
    if len(rows) != 1: raise ValueError('missing or ambiguous metric')
    value = float(rows[0]['value'][1])
    if not math.isfinite(value): raise ValueError('non-finite metric')
    return value

def age_hours(value, now):
    age = (now - dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()) / 3600
    if age < -0.02: raise ValueError('future timestamp')
    return max(0, age)

def backup_check(data, restore, now):
    age = age_hours(data.get('lastRestoreAt' if restore else 'lastSuccessAt', ''), now)
    latest = data.get('lastRestoreState' if restore else 'lastAttemptState')
    fresh = age <= (192 if restore else 30)
    healthy = latest == 'ok' and fresh
    if latest == 'running' and fresh:
        try:
            attempt_age = age_hours(data.get('lastRestoreAttemptAt' if restore else 'lastAttemptAt', ''), now)
            healthy = attempt_age <= 20 / 60
        except (ValueError, TypeError, AttributeError):
            healthy = False
    return ('ok' if healthy else 'failed', round(age, 2), 'hours')

def capacity_check(data, now):
    age = age_hours(data.get('generatedAt', ''), now)
    if age > 3/60 or data.get('status') not in ('ok', 'warning', 'failed'):
        return ('unknown', None, '')
    return ('ok' if data['status'] == 'ok' else 'failed', None, '')

def collect(now):
    result = {}
    def check(key, operation):
        try: result[key] = operation()
        except Exception: result[key] = ('unknown', None, '')
    def metric(key, expression, healthy, unit=''):
        def get():
            value = query(expression, now)
            return ('ok' if healthy(value) else 'failed', round(value, 3), unit)
        check(key, get)
    manifest = None
    try: manifest = read_json(ROOT/'deploy/state/release.json')
    except Exception: pass
    active = manifest.get('active') if manifest else None
    if active not in ('blue', 'green'): active = None
    # Inspect the actual serving slot; intentionally stopped standby is not an outage.
    def gateway():
        import blue_green
        if not active: raise ValueError('unknown active slot')
        colors = [active]
        if manifest.get('phase') in ('preparing', 'switching', 'draining'): colors = ['blue', 'green']
        healthy = False
        for color in colors:
            try:
                state = blue_green.api(color, timeout=2)
                healthy = healthy or (state.get('ready') is True and state.get('unsettled') == 0)
            except Exception: pass
        return ('ok' if healthy else 'failed', int(healthy), '')
    check('gateway', gateway)
    selector = '{job="subscription-meter",instance="app-' + active + ':8180"}' if active else '{job="subscription-meter"}'
    if manifest and manifest.get('phase') in ('preparing', 'switching', 'draining'): selector = '{job="subscription-meter"}'
    metric('prometheus', 'max(up' + selector + ')', lambda v: v == 1)
    metric('worker_ready', 'min(control_usage_audit_worker_ready and on(instance,job) (up{job="control-usage-audit-worker"} == 1))', lambda v: v == 1)
    metric('worker_lag', 'max(control_usage_audit_worker_records_lag and on(instance,job) (up{job="control-usage-audit-worker"} == 1))', lambda v: 0 <= v <= 1024, 'records')
    metric('audit_guard', 'max(meter_audit_guard_ready' + selector + ' and on(instance,job) (up' + selector + ' == 1))', lambda v: v == 1)
    if result.get('prometheus', ('unknown',))[0] == 'ok':
        metric('upstream_auth', 'sum(increase(meter_requests_total{status=~"401|403"}[10m])) or vector(0)', lambda v: v == 0, 'responses / 10 min')
    else:
        result['upstream_auth'] = ('unknown', None, 'responses / 10 min')
    def disk():
        usage = shutil.disk_usage(ROOT)
        free_gib = usage.free / 1024**3
        return ('ok' if free_gib >= 10 and usage.free / usage.total >= .10 else 'failed', round(free_gib, 2), 'GiB free')
    check('disk', disk)
    for restore, key in ((False, 'backup'), (True, 'restore')):
        check(key, lambda restore=restore: backup_check(read_json(REPORT_DIR/'backup-status.json'), restore, now))
    check('backup_capacity', lambda: capacity_check(read_json(REPORT_DIR/'backup-capacity.json'), now))
    return result

def report(checks, previous, now):
    prior_rows = previous.get('checks', [])
    old = {c.get('id'): c for c in prior_rows if isinstance(c, dict) and c.get('id') in CHECKS} if isinstance(prior_rows, list) else {}
    previous_fresh = False
    try: previous_fresh = 0 <= now - dt.datetime.fromisoformat(previous['generatedAt']).timestamp() <= 180
    except Exception: pass
    rows = []
    transitions = []
    for key in CHECKS:
        state, value, unit = checks.get(key, ('unknown', None, ''))
        prior = old.get(key, {})
        prior_count = prior.get('consecutiveFailures', 0)
        if type(prior_count) is not int or not 0 <= prior_count <= 999: prior_count = 0
        failures = min(999, prior_count + 1) if state != 'ok' and previous_fresh else int(state != 'ok')
        alerting = failures >= 3
        if alerting and not prior.get('alerting'): transitions.append('ALERT ' + key)
        if state == 'ok' and prior.get('alerting'): transitions.append('RECOVERED ' + key)
        rows.append({'id': key, 'state': state, 'value': value, 'unit': unit, 'consecutiveFailures': failures, 'alerting': alerting})
    status = 'ok' if all(c['state'] == 'ok' for c in rows) else ('alert' if any(c['alerting'] for c in rows) else 'degraded')
    return {'schemaVersion': 1, 'generatedAt': dt.datetime.fromtimestamp(now, dt.timezone.utc).isoformat(), 'status': status, 'checks': rows}, transitions

def main():
    os.umask(0o077)
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    with (REPORT_DIR/'monitor.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try: previous = read_json(REPORT_DIR/'report.json')
        except Exception: previous = {}
        result, transitions = report(collect(time.time()), previous, time.time())
        target = REPORT_DIR/'report.json'; temporary = REPORT_DIR/'report.tmp'
        with temporary.open('w') as stream: json.dump(result, stream, separators=(',', ':'), allow_nan=False)
        temporary.chmod(0o644)  # Fixed, sanitized metadata readable by the app's read-only mount.
        os.replace(temporary, target)
        for transition in transitions: print('control-usage operations ' + transition, flush=True)
        print('control-usage operations status=' + result['status'], flush=True)

if __name__ == '__main__':
    try: main()
    except Exception:
        print('control-usage operations collection failed; report freshness will show unavailable', flush=True)
        raise SystemExit(1)
