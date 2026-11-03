"""Bounded real-upstream check: one short message, then streaming only if accepted."""
from admin_auth import authorize
import argparse
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('url')
parser.add_argument('--model', required=True)
args = parser.parse_args()
base = args.url.rstrip('/')
parsed = urllib.parse.urlsplit(base)
assert parsed.scheme == 'https' and (parsed.hostname or '').endswith('.trycloudflare.com') and not parsed.path
assert not parsed.username and not parsed.query and not parsed.fragment
root = Path(__file__).resolve().parents[1]
admin = (root / 'secrets/admin-token').read_text().strip()
local = 'http://127.0.0.1:8180'


def request(origin, path, token, body=None, method=None):
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
               'anthropic-version': '2023-06-01', 'User-Agent': 'subscription-meter-live-test/0.1'}
    return authorize(urllib.request.Request(origin + path, data=json.dumps(body).encode() if body is not None else None,
                                  headers=headers, method=method), root, admin)


def call(origin, path, token, body=None, method=None):
    try:
        response = urllib.request.urlopen(request(origin, path, token, body, method), timeout=60)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read(), response.headers


def inspect(request_id):
    for _ in range(20):
        code, raw, _ = call(local, '/admin/requests/' + request_id, admin)
        assert code == 200
        detail = json.loads(raw)
        if detail['state'] != 'pending':
            return detail
        time.sleep(.25)
    raise AssertionError('Request ledger did not finalize')


status = json.loads(call(local, '/admin/status', admin)[1])
assert status['mode'] == 'subscription' and status['credentialConfigured'], 'Live mode with a provisioned credential required'
report = {'url': base, 'model': args.model, 'mode_during_test': 'subscription', 'live_generation_verified': False}
created = json.loads(call(local, '/admin/members', admin,
                          {'name': 'live-smoke-' + uuid.uuid4().hex[:8], 'rpm': 10, 'concurrency': 1})[1])
key = created['key']
try:
    payload = {'model': args.model, 'max_tokens': 32, 'stream': False,
               'messages': [{'role': 'user', 'content': 'Reply with exactly: gateway test ok'}]}
    code, raw, headers = call(base, '/gateway/v1/messages', key, payload)
    report['nonstream_status'] = code
    request_id = headers.get('x-meter-request-id')
    report['request_id'] = request_id
    response = json.loads(raw)
    detail = inspect(request_id) if request_id else None
    if detail:
        assert detail['member_id'] == created['member']['id'] and detail['upstream_mode'] == 'subscription'
        assert detail['status_code'] == code
        assert json.loads(detail['request_body']) == payload
        assert key not in (detail.get('request_headers') or '')
        report['attribution_and_request_capture_verified'] = True
        report['ledger_state'] = detail['state']
        report['upstream_request_id'] = detail.get('upstream_request_id')
    if code != 200:
        error = response.get('error', {})
        message = str(error.get('message', 'Upstream rejected request'))
        for secret in (key, admin, (root / 'secrets/upstream-token').read_text().strip()):
            if secret:
                message = message.replace(secret, '[REDACTED]')
        report['error_type'] = error.get('type')
        report['error_message'] = re.sub(r'sk-ant-[A-Za-z0-9_-]+', '[REDACTED]', message)[:1000]
        if detail:
            report['error_response_captured'] = bool(detail.get('response_body'))
    else:
        assert response['type'] == 'message' and detail['state'] == 'complete'
        assert json.loads(detail['response_body']) == response
        assert detail['input_tokens'] == response['usage']['input_tokens']
        assert detail['output_tokens'] == response['usage']['output_tokens']
        report['nonstream_usage_verified'] = True
        report['usage'] = response['usage']
        started = time.monotonic()
        events, arrivals = [], []
        with urllib.request.urlopen(request(base, '/gateway/v1/messages', key, {**payload, 'stream': True}), timeout=60) as stream:
            assert stream.status == 200 and 'text/event-stream' in stream.headers.get('Content-Type', '')
            stream_id = stream.headers['x-meter-request-id']
            for line in stream:
                if line.startswith(b'data:'):
                    events.append(json.loads(line[5:]))
                    arrivals.append(time.monotonic() - started)
        assert events[0]['type'] == 'message_start' and events[-1]['type'] == 'message_stop'
        assert not any(e['type'] == 'error' for e in events)
        streamed = inspect(stream_id)
        assert streamed['member_id'] == created['member']['id'] and streamed['state'] == 'complete'
        stored = json.loads(call(local, '/admin/requests/' + stream_id + '/events', admin)[1])
        assert len(stored) == len(events)
        report.update({'live_generation_verified': True, 'stream_request_id': stream_id, 'stream_events': len(events),
                       'first_event_seconds': round(arrivals[0], 3), 'last_event_seconds': round(arrivals[-1], 3),
                       'stream_capture_verified': True})
finally:
    assert call(local, '/admin/members/' + created['member']['id'], admin, method='DELETE')[0] == 200
    report['test_key_revoked'] = True
    report['revoked_key_rejected'] = call(base, '/gateway/v1/models', key)[0] == 401
    (root / 'artifacts').mkdir(exist_ok=True)
    (root / 'artifacts/live-smoke.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
if not report['live_generation_verified']:
    raise SystemExit(2)
