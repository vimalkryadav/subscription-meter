"""Probe a freshly created Quick Tunnel using a temporary member key and mock requests only."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("url")
args = parser.parse_args()
base = args.url.rstrip("/")
parsed = urllib.parse.urlsplit(base)
assert parsed.scheme == "https" and (parsed.hostname or "").endswith(".trycloudflare.com") and not parsed.path
assert not parsed.username and not parsed.query and not parsed.fragment
root = Path(__file__).resolve().parents[1]
admin = (root / "secrets/admin-token").read_text().strip()


def request(origin, path, token="", body=None, method=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    return urllib.request.Request(origin + path, data=data, headers=headers, method=method)


def call(origin, path, token="", body=None, method=None):
    try:
        response = urllib.request.urlopen(request(origin, path, token, body, method), timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read()


local = "http://127.0.0.1:8180"
status = json.loads(call(local, "/admin/status", admin)[1])
assert status["mode"] == "mock", "This test must not consume subscription usage"
for path in ("/", "/admin/status", "/admin/requests", "/actuator/prometheus", "/actuator/health", "/v1/messages"):
    code, _ = call(base, path)
    assert code == 404, (path, code)
assert call(base, "/v1/models")[0] == 401
created = json.loads(call(local, "/admin/members", admin,
                         {"name": "tunnel-smoke-" + uuid.uuid4().hex[:8], "rpm": 100, "concurrency": 20})[1])
report = {"url": base, "mode": "mock", "private_routes_blocked": True, "member_auth_required": True}
try:
    key = created["key"]
    assert call(base, "/v1/models", key)[0] == 200
    payload = {"model": "mock-stream-check", "max_tokens": 32, "stream": True,
               "messages": [{"role": "user", "content": "Test incremental transport"}]}
    started = time.monotonic()
    arrivals = []
    types = []
    with urllib.request.urlopen(request(base, "/v1/messages", key, payload), timeout=30) as response:
        assert response.status == 200 and "text/event-stream" in response.headers.get("Content-Type", "")
        for line in response:
            if line.startswith(b"data:"):
                event = json.loads(line[5:])
                arrivals.append(time.monotonic() - started)
                types.append(event["type"])
    assert types[0] == "message_start" and types[-1] == "message_stop"
    report.update({"events": len(arrivals), "first_event_seconds": round(arrivals[0], 3),
                   "last_event_seconds": round(arrivals[-1], 3),
                   "incremental_streaming_observed": arrivals[-1] - arrivals[0] >= 1.5})

    def concurrent_stream(_):
        code, body = call(base, "/v1/messages", key, {**payload, "model": "mock-model"})
        assert code == 200 and b'"type": "message_stop"' in body
        return True

    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
        report["concurrent_streams_completed"] = sum(executor.map(concurrent_stream, range(20)))
finally:
    call(local, "/admin/members/" + created["member"]["id"], admin, method="DELETE")
report["revoked_key_rejected"] = call(base, "/v1/models", created["key"])[0] == 401
(root / "artifacts").mkdir(exist_ok=True)
(root / "artifacts/tunnel-smoke.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
if not report["incremental_streaming_observed"]:
    raise SystemExit("Stream arrived buffered; do not treat this tunnel as a working streaming gateway")
