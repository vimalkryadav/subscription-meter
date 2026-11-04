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

## Deployed acceptance update — 2026-11-05

The initial admission pause and migration have completed. Kafka audit transport,
shared coordination and automatic GitHub-to-Coder blue/green deployment are live.
The dedicated scoped automation credential is installed. PR7 deployed release
`f93744e440a1bfa0b46f13c61eda6ab91f17648f`; readiness, audit readiness, unchanged
shared resources and the sole administrator were verified after cutover.

Playwright over CDP verified all seven administrator views at 1440, 768 and 390px,
using deployed assets after removing local preview overrides. Reporting APIs
returned200, document overflow and clipped chart labels were absent, custom
selects worked, and the browser had no console errors or warnings. Signed-out
admin routes remained protected. These checks do not replace ongoing usability
or accessibility review.

Remaining external acceptance:

- Sustained real-provider traffic and long-running streams across a live cutover
  have not been validated at20-user load. Local byte-exact stream switch/rollback
  tests are separate evidence and exclude Cloudflare and provider behavior.
- Codex remains disabled by the owner's choice. Dedicated hosted-app setup and
  real end-to-end generation/tool continuation remain unverified.
- Quick Tunnel and single-workspace availability limits remain. Backups, health
  checks and operational limits are described in [operations](operations.md).
