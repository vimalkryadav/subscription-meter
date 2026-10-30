"""20-member acceptance test against mock mode. Saves no member keys; revokes fixtures afterward."""
import concurrent.futures
import json
from pathlib import Path
import time
import urllib.request
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = "http://127.0.0.1:8180"
ADMIN = (ROOT / "secrets/admin-token").read_text().strip()


def call(path, token=ADMIN, data=None, method=None):
    request = urllib.request.Request(BASE + path, data=json.dumps(data).encode() if data else None,
                                    headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.status, response.read()


status = json.loads(call("/admin/status")[1])
assert status["mode"] == "mock", "Smoke test must not consume live subscription usage"
run = uuid.uuid4().hex[:8]
members = []
try:
    for i in range(20):
        members.append(json.loads(call("/admin/members", data={"name": f"smoke-{run}-{i:02}", "rpm": 60, "concurrency": 4})[1]))

    def use(member):
        start = time.monotonic()
        status_code, body = call("/v1/messages", member["key"], {"model": "mock-model", "max_tokens": 32, "stream": True, "messages": [{"role": "user", "content": "hello"}]})
        assert status_code == 200 and b'"type": "message_stop"' in body
        return time.monotonic() - start

    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
        durations = list(executor.map(use, members))
    ids = {m["member"]["id"] for m in members}
    # Final ledger writes happen just after the final bytes reach the client.
    for _ in range(30):
        rows = [r for r in json.loads(call("/admin/analytics")[1])["rows"] if r["member_id"] in ids]
        if len(rows) == 20 and all(r["state"] == "complete" for r in rows):
            break
        time.sleep(0.1)
    assert len(rows) == 20 and all(r["state"] == "complete" and r["requests"] == 1 for r in rows), rows
    for row in rows:
        assert [row[k] for k in ("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")] == [120, 17, 30, 80]
    report = {"mode": "mock", "users": 20, "successful_streams": 20, "attribution_verified": True,
              "input_tokens": 2400, "output_tokens": 340, "cache_creation_tokens": 600, "cache_read_tokens": 1600,
              "max_request_seconds": round(max(durations), 3), "runtime": status["runtime"], "thread": status["thread"]}
    (ROOT / "artifacts").mkdir(exist_ok=True)
    (ROOT / "artifacts/smoke.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
finally:
    for member in members:
        call("/admin/members/" + member["member"]["id"], method="DELETE")
if members:
    try:
        call("/v1/models", members[0]["key"])
        raise AssertionError("Revoked key accepted")
    except urllib.error.HTTPError as e:
        assert e.code == 401
print("Revocation verified; all smoke-test keys revoked.")
