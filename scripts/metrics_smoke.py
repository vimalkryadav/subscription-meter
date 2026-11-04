"""Read-only checks for the deployed admin dashboard; provision an admin session first."""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.request
from admin_auth import authorize

parser = argparse.ArgumentParser()
parser.add_argument("url")
base = parser.parse_args().url.rstrip("/")
root = Path(__file__).resolve().parents[1]
admin = (root / "secrets/admin-token").read_text().strip()

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener = urllib.request.build_opener(NoRedirect())


def read(path, authenticated=True):
    headers = {"Authorization": "Bearer " + admin} if authenticated else {}
    request = authorize(urllib.request.Request(base + path, headers=headers), root, admin)
    try:
        response = opener.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


for path, marker, mime in (
    ("/manage", b'id="metricsData"', "text/html"),
    ("/assets/admin.css", b".sidebar", "text/css"),
    ("/assets/admin.js", b"connectAdmin", "javascript"),
    ("/assets/charts.js", b"MeterCharts", "javascript"),
    ("/assets/metrics.js", b"refreshMetrics", "javascript"),
    ("/assets/dropdown.js", b"data-dropdown", "javascript"),
    ("/assets/control-usage.svg", b"control-usage", "image/svg+xml"),
):
    code, headers, data = read(path, path == "/manage")
    assert code == 200 and marker in data and mime in headers.get("Content-Type", ""), path

assert read("/admin/metrics", False)[0] == 401
for path in ("/manage", "/manage/"):
    code, headers, data = read(path, False)
    assert code == 303 and headers.get("Location") == "/" and b"metricsData" not in data
assert read("/manage.html", False)[0] == 404
assert read("/admin/metrics?hours=10000")[0] == 400
assert read("/admin/metrics?mode=invalid")[0] == 400
assert read("/admin/metrics?mode=mock")[0] == 400
code, _, data = read("/admin/status")
assert code == 200 and json.loads(data)["mode"] == "subscription"
checked = 0
for hours in (1, 6, 24, 168):
    for mode in ("all", "subscription"):
        code, _, data = read(f"/admin/metrics?hours={hours}&mode={mode}")
        assert code == 200, (hours, mode, code)
        report = json.loads(data)
        summary = report["summary"]
        assert report["mode"] == mode
        for field in ("requests", "tokens", "completed", "failed", "pending"):
            assert sum(row[field] for row in report["traffic"]) == summary[field], field
        assert len(report["traffic"]) <= hours * 3600 // report["stepSeconds"] + 1
        assert len(report["topMembers"]) <= 10 and len(report["topModels"]) <= 10
        for row in [summary, *report["timings"]]:
            values = [row.get("duration_" + p) for p in ("p50", "p95", "p99")]
            if values[0] is not None:
                assert 0 <= values[0] <= values[1] <= values[2]
        checked += 1
print(json.dumps({"metrics_combinations_checked": checked, "assets": "passed",
                  "admin_authentication": "required", "aggregate_consistency": "passed"}))
