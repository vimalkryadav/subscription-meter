"""Verify full capture, unchanged upstream payload, redaction, errors, and access audit."""
from pathlib import Path
import hashlib
import json
import time
import urllib.request
import urllib.error
import uuid

root = Path(__file__).resolve().parents[1]
admin = (root / "secrets/admin-token").read_text().strip()
base = "http://127.0.0.1:8180"


def call(path, token=admin, body=None, method=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method,
                                    headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


assert json.loads(call("/admin/status")[2])["mode"] == "mock"
issued = json.loads(call("/admin/members", body={"name": "audit-smoke-" + uuid.uuid4().hex[:8], "rpm": 60, "concurrency": 4})[2])
try:
    payload = {"model": "mock-audit", "stream": True, "max_tokens": 32,
               "system": "System instructions", "metadata": {"password": "mock-input-secret"},
               "tools": [{"name": "read_file", "input_schema": {"type": "object"}}],
               "messages": [{"role": "user", "content": "Read example.java"}]}
    status, headers, body = call("/v1/messages?beta=true&access_token=mock-query-secret", issued["key"], payload)
    assert status == 200 and b"mock-output-secret" in body, "Client stream must remain unmodified"
    request_id = headers["x-meter-request-id"]
    for _ in range(50):
        detail = json.loads(call("/admin/requests/" + request_id)[2])
        if detail["capture_state"] != "pending":
            break
        time.sleep(0.1)
    events = json.loads(call("/admin/requests/" + request_id + "/events")[2])
    snapshot = json.dumps({"detail": detail, "events": events})
    assert "mock-input-secret" not in snapshot and "mock-output-secret" not in snapshot
    assert "mock-query-secret" not in snapshot and issued["key"] not in snapshot
    assert "never-store-this-cookie" not in snapshot
    assert len(events) == 9 and "read_file" in snapshot and "example.java" in snapshot
    assert json.loads(detail["response_headers"])["x-mock-request-sha256"] == [hashlib.sha256(json.dumps(payload).encode()).hexdigest()]
    assert detail["capture_state"] == "complete" and detail["response_bytes"] == len(body)
    status, headers, body = call("/v1/messages", issued["key"], {"model": "mock-error", "messages": []})
    assert status == 429
    error_id = headers["x-meter-request-id"]
    for _ in range(50):
        failure = json.loads(call("/admin/requests/" + error_id)[2])
        if failure["capture_state"] != "pending":
            break
        time.sleep(0.1)
    assert "Synthetic rate limit" in failure["response_body"] and failure["status_code"] == 429
    status, _, _ = call("/v1/models", "invalid-member-key")
    assert status == 401
    access = json.loads(call("/admin/access")[2])
    assert any(r["status_code"] == 401 for r in access)
    assert call("/admin/requests/" + request_id, "invalid-admin-key")[0] == 401
    print(json.dumps({"full_prompt_and_tools": True, "ordered_tool_events": len(events),
                      "credential_redaction": True, "forwarded_payload_unchanged": True,
                      "upstream_error_body": True, "rejected_access": True, "admin_only": True}, indent=2))
finally:
    call("/admin/members/" + issued["member"]["id"], method="DELETE")
