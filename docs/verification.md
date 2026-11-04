# Release verification — 2026-11-04

All results below are isolated tests. No production member accounts, roles,
credentials, gateway traffic, edge routes or BugPolice resources were changed.
No real inference request was sent during this implementation.

## Audit stream experiment

`AuditStreamLoadTest` uses the actual gateway service, Kafka publisher, usage
observer and PostgreSQL projector with generated upstream bytes. Each response
contains 53 SSE events, including split UTF-8, delivered in 113-byte input chunks.
The test requires byte-identical client output, all events, complete capture,
exact output-token totals and complete ledger projection. PostgreSQL and Kafka
run in the local `control-usage-test` Docker project. The worker uses a bounded
connection pool and 16-record transaction batches.

| Concurrent requests | Events | First forwarded bytes p95 | Request duration p95 | All results searchable |
|---:|---:|---:|---:|---:|
| 1 | 53 | 126 ms | 462 ms | 3,434 ms |
| 5 | 265 | 30 ms | 329 ms | 4,104 ms |
| 20 | 1,060 | 30 ms | 261 ms | 6,942 ms |

These are single-run samples, not capacity guarantees or end-to-end provider
latencies. The first run includes cold initialization; searchability includes
consumer group startup and projection. Cloudflare and Anthropic are absent.
The direct publisher fixture omits the production capacity guard; separate
Kafka safety tests exercise that guard and its failure/recovery behavior.

An earlier serialized-publisher implementation measured 493 ms first-forward
p95 and 6,754 ms request-duration p95 at 20 concurrent requests. Bounded concurrent
publishing removed that bottleneck. Projection remains eventually consistent;
the UI must distinguish pending and incomplete capture from complete results.

Reproduce:

```sh
docker compose -f compose.audit-test.yaml up -d
AUDIT_INTEGRATION=true mvn -Dtest=AuditStreamLoadTest test
```

Reports are written to ignored `target/benchmarks/`. Use only the isolated fixture;
never redirect these tests to production databases or topics.

## Streaming deployment

The pinned Caddy 2.11.7 image, `admin off`, and bind-mounted configuration were
tested with 20 simultaneous streams lasting 35 seconds. Ten remained on blue
through a switch; ten started on green and survived rollback. Every stream
retained all 70 expected events byte-for-byte. A rejected configuration reload
left the old route active. The actual route allowlist's bootstrap admission
pause returned 503 with Retry-After while an existing stream finished.

This proves the tested local edge reload behavior. It does not establish
Cloudflare Quick Tunnel SSE support or protection against workspace, database,
broker, disk or tunnel failure. The initial legacy-to-shared-admission handoff
requires an explicitly authorized pause; normal blue/green releases use draining.

## Packaged application and regression checks

`mvn verify` passed: 70 regular tests, with eight integration tests intentionally
skipped by their environment gates. The separately enabled Kafka, coordination,
load and deployment fixtures passed as described above. Eight Python deployment/
quota tests and three JavaScript interaction tests also passed.

The packaged executable jar started against a newly migrated PostgreSQL schema.
Lease/audit readiness, unauthenticated admin rejection, bootstrap-authenticated
search/accounting/account/status APIs and the disabled Codex route all passed.
Reproduce with `python3 scripts/tests/packaged_smoke.py` after building the jar
and starting the isolated dependencies. It sends no inference request.

The packaged worker also passed independent process startup: consumer-group
bootstrap, readiness, real BEGIN projection and metrics. A malformed record
stopped the process without logging its raw payload.

## Remaining external acceptance

- Apply the reviewed release on Coder only after the initial admission-pause
  exception is authorized; retain the sole administrator and all live data.
- Confirm the actual Cloudflare URL, signed-out/member/admin boundaries and
  long-running streaming behavior after cutover.
- Browser-based visual/accessibility verification was unavailable in this run.
  JavaScript interaction, markup and API tests do not replace visual inspection.
- The optional Codex/SIWC adapter remains disabled. Hosted eligibility,
  dedicated credential provisioning and real end-to-end generation are unverified.
- GitHub deployment needs a dedicated Coder automation credential. No personal
  local token has been copied into repository secrets.
