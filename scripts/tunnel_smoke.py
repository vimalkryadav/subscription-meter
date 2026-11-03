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
code, landing = call(base, "/")
assert code == 200 and b'Member portal' in landing and b'id="authForm"' in landing
code, dashboard = call(base, "/manage")
assert code == 200 and b'Request inspector' in dashboard and b'id="login"' in dashboard
gateway = base + "/gateway"
for path in ("/actuator/prometheus", "/actuator/health", "/v1/messages", "/v1/models",
             "/gateway/admin/status", "/gateway/actuator/health", "/gateway/v1/messages"):
    code, _ = call(base, path)
    assert code == 404, (path, code)
assert call(base, "/v1/messages", body={})[0] == 404
admin_paths = ("/admin/status", "/admin/members", "/admin/analytics", "/admin/requests", "/admin/access", "/admin/key-requests",
               "/admin/requests/nonexistent", "/admin/requests/nonexistent/events")
for path in admin_paths:
    assert call(base, path)[0] == 401, path
    assert call(base, path, "invalid-admin-key")[0] == 401, path
assert call(base, "/admin/members", body={})[0] == 401
assert call(base, "/admin/members/nonexistent", method="DELETE")[0] == 401
assert call(gateway, "/v1/models")[0] == 401
assert call(gateway, "/v1/models", admin)[0] == 401
assert call(gateway, "/v1/messages", body={})[0] == 401
assert call(gateway, "/v1/messages/count_tokens", body={})[0] == 401
assert json.loads(call(base, "/admin/status", admin)[1])["mode"] == "mock"
for path in admin_paths[:5]:
    assert call(base, path, admin)[0] == 200, path
created = json.loads(call(base, "/admin/members", admin,
                         {"name": "tunnel-smoke-" + uuid.uuid4().hex[:8], "rpm": 100, "concurrency": 20})[1])
report = {"url": base, "gateway_base": gateway, "mode": "mock", "dashboard_accessible": True,
          "admin_auth_required": True, "internal_routes_blocked": True, "member_auth_required": True}
try:
    key = created["key"]
    for path in admin_paths:
        assert call(base, path, key)[0] == 401, path
    assert call(base, "/admin/members", key, body={})[0] == 401
    assert call(base, "/admin/members/" + created["member"]["id"], key, method="DELETE")[0] == 401
    assert call(gateway, "/v1/models", key)[0] == 200
    payload = {"model": "mock-stream-check", "max_tokens": 32, "stream": True,
               "messages": [{"role": "user", "content": "Test incremental transport"}]}
    started = time.monotonic()
    arrivals = []
    types = []
    with urllib.request.urlopen(request(gateway, "/v1/messages", key, payload), timeout=30) as response:
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
        code, body = call(gateway, "/v1/messages", key, {**payload, "model": "mock-model"})
        assert code == 200 and b'"type": "message_stop"' in body
        return True

    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
        report["concurrent_streams_completed"] = sum(executor.map(concurrent_stream, range(20)))
    rows = json.loads(call(base, "/admin/requests?memberId=" + created["member"]["id"], admin)[1])
    # The successful model-list request is captured as well as the 21 streams.
    assert len(rows) == 22, len(rows)
    streams = [row for row in rows if row["model"] in ("mock-model", "mock-stream-check")]
    assert len(streams) == 21, len(streams)
    detail_path = "/admin/requests/" + streams[0]["id"]
    detail = json.loads(call(base, detail_path, admin)[1])
    assert "messages" in json.loads(detail["request_body"])
    events = json.loads(call(base, detail_path + "/events", admin)[1])
    assert len(events) == 6, len(events)
    for path in (detail_path, detail_path + "/events"):
        assert call(base, path)[0] == 401
        assert call(base, path, key)[0] == 401
    report["request_inspector_verified"] = True
    code, counted = call(gateway, "/v1/messages/count_tokens", key, payload)
    assert code == 200 and json.loads(counted)["input_tokens"] == 120
    code, message = call(gateway, "/v1/messages", key, {**payload, "stream": False})
    assert code == 200 and json.loads(message)["type"] == "message"
    report["token_count_and_nonstream_verified"] = True
    assert call(base, "/admin/members/" + created["member"]["id"], admin, method="DELETE")[0] == 200
    report["public_member_management_verified"] = True
finally:
    # Local fallback guarantees cleanup even if the public tunnel fails midway.
    assert call(local, "/admin/members/" + created["member"]["id"], admin, method="DELETE")[0] == 200
report["revoked_key_rejected"] = call(gateway, "/v1/models", created["key"])[0] == 401
assert report["revoked_key_rejected"]
(root / "artifacts").mkdir(exist_ok=True)
(root / "artifacts/tunnel-smoke.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
if not report["incremental_streaming_observed"]:
    raise SystemExit("Stream arrived buffered; do not treat this tunnel as a working streaming gateway")
