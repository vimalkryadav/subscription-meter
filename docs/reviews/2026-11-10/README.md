# control-usage improvement council — 10 November 2026

The recommendation is to keep the current workspace for now, address backup headroom and response correctness first, then improve Windows onboarding and request investigation. A new Coder workspace is an available option if sustained measurements show a bottleneck or a separately chosen recovery/isolation objective warrants it. Current measurements do not demonstrate host, database-pool or worker saturation.

This is a review and implementation plan, not a claim that the improvements below have shipped. The preceding persistent Claude setup **has shipped** in [PR #18](https://github.com/vimalkryadav/subscription-meter/pull/18), release `596d7f7d187d0e9eeb72cfeafb8f322bd8bce00f`. Its CI and blue/green release succeeded; live installer content, commands, responsive layout and readiness passed. Shared database, broker, worker, edge, tunnel and sole-admin roles remained unchanged. On supported macOS/Linux terminals, ordinary `claude` now uses persistent user-level settings after one setup command; undo and an optional separate profile are available.

## Council process and scope

Three independent agents reviewed UI/frontend, backend, and database. The coordinator reviewed operations/resource capacity, checked source evidence, and exchanged findings between reviewers for challenge and consolidation. Detailed specialist reports are retained here:

- [UI and frontend: nine findings, live browser measurements, acceptance criteria](ui.md)
- [Backend: nine findings, phase/memory analysis, failure and replay tests](backend.md)
- [Database: eight findings, bounded query measurements, storage and recovery](database.md)

Review baseline is the release above, sampled around 14:25–14:35 UTC on 10 November 2026. Production review was read-only: aggregate metrics, bounded read-only SQL, and authenticated dashboard navigation. No model requests, synthetic production members, captured prompt inspection, production load tests, schema changes, data cleanup, restarts or workspace provisioning were performed for the council. Browser authentication used a temporary private session; no credentials or captured content belong in this report.

Constraints carried forward: Java 26/Spring Boot, React/TypeScript, SOLID boundaries, dark-only UI, custom dropdowns, one administrator, Codex disabled, complete bounded capture, acknowledged Kafka before forwarding, existing retention, and no changes to BugPolice resources. Additional Coder capacity is permitted for a demonstrated need; that does not authorize paid infrastructure purchases or justify a speculative move.

## What was measured

These are snapshots or explicitly bounded observation windows, not service guarantees or a 20-user capacity benchmark.

| Area | Observation | Interpretation and limit |
| --- | --- | --- |
| Host | 4 CPUs; approximately 15.4 GiB RAM, 10.6 GiB available; load 0.79 / 1.17 / 1.07 | No demonstrated host exhaustion in this snapshot; historical peaks and other-workload contention still need observation |
| Container memory | App 611 MiB / 1 GiB; worker 459 MiB / 768 MiB; broker 684 MiB / 1.5 GiB; database 341 MiB / 1 GiB | Free host RAM does not remove individual container/heap limits |
| CPU limits | Worker 0.5 CPU, broker 1 CPU | Measure throttling and lag before changing limits; reserve capacity for both app colors |
| Storage | Database 1.266 GiB; backup filesystem approximately 179 GiB free; Docker filesystem approximately 52 GiB free | Physical free space is ample; configured application limits are the nearer constraint |
| Backup | Latest archive 526,465,997 bytes out of 536,870,912 | **98.06% of archive limit; only 9.92 MiB remains.** Next-backup size is unknown |
| Capture storage | Request-body stored-column sizes approximately 834 MB; response-body sizes approximately 71 KB | Incoming captured bodies dominate this sample; these are stored sizes, not raw content or a cross-request duplication measurement |
| Audit identities | Approximately 243 MB / 499k estimated operation rows | Durable replay identities grow outside capture hypertable retention; they are not disposable logs |
| Accounting no-op | 167 ms execution + 29 ms planning; 97,185 shared-buffer hits; zero returned work | Avoidable repeated logical access, **not 97k disk reads**; no user-facing pool contention demonstrated |
| Candidate accounting query | 26 ms execution; 17,688 buffer hits | Different snapshot returned one item; directional result only, not proven equivalence or an end-to-end 6× improvement |
| Database pool, 30 min | Pending maximum 0; active maximum 1; mean acquire 0.208 ms | No reason from this window to enlarge the pool or add Redis coordination |
| Audit publication, 30 min | Mean 18.35 ms; observed rolling maximum 386.9 ms; recent five-minute mean 29.32 ms | Combined timer includes local waiting/encoding/send/ack; not broker-only latency or p95 |
| Worker, 30 min | Ready; current lag 0, observed maximum 369 records | Below the 4,096-record guard; not evidence for another consumer or partition change |
| Metrics navigation | Approximately 440 ms TTFB, 884 ms DOMContentLoaded/load; 302 KB decoded JS / 93 KB transferred | One unthrottled browser navigation, not LCP, INP, p95 or server-only timing |
| Explorer | Search 535 ms / 24.7 KB decoded; analytics 246 ms / 3.6 KB decoded; access 341 ms / 98.1 KB decoded | Three parallel public-URL reads. Access fetch is large relative to its five displayed columns |
| Browser layout | No page errors or document overflow at tested desktop/mobile widths | Does not replace full keyboard, screen-reader, low-end-device or Windows testing |

## Decisions from cross-review

1. **Backup capacity precedes visual polish.** The small number is remaining margin, not archive size. Source database, archive, backup-store and restore budgets must change together. Do not delete real history or protected backups to fit a small constant.
2. **Query optimization precedes database relocation.** The accounting poll has measured repeated work. A remote database would add network round trips to that work and to exact admission without addressing its cause.
3. **A metadata-only request inspector helps every layer.** Fetching complete captured bodies for the Summary tab wastes database retrieval, response bytes and browser parsing. Add compatible endpoints first, then lazy UI tabs, while keeping the administrator's full authorized access.
4. **Response preparation is not response commitment.** Source review found a real state-model mismatch; fault-injection acceptance must establish HTTP, ledger and capture behavior before a fix is declared complete.
5. **Memory bounds are a credible capacity risk, not a current OOM.** Eighty permitted requests can theoretically retain more response memory than one process has. Current low concurrency does not validate the maximum legal workload.
6. **Incoming bodies are today's storage priority.** Duplicate nonstream response publication exists, but only about 71 KB of response bodies was observed. Defer that durable-contract change behind larger measured issues.
7. **Existing latency wording is not a bug.** Metrics already explains first upstream response bytes. Add separate admission, audit, forwarding and finalization measurements; do not rename upstream latency as client experience.
8. **Windows requires a real implementation and test surface.** Git Bash does not make Unix Python locking/permissions portable. Native PowerShell, WSL and macOS/Linux must be distinguished.
9. **Keep the working architecture.** No measured case supports replacing React/Spring, adding Cassandra, Redis queues, more Kafka consumers or a frontend rendering framework. Address specific responsibilities and data movement first.

## Prioritized delivery plan

Priority P1 means near-term correctness, recovery or material workflow work, not a claim of a current incident. P2 means subsequent targeted improvement or a measurement-gated experiment. Effort is rough engineering/review/test effort: S about 0.5–1 day, M about 2–4 days, L about 5+ days. It is not a promised calendar schedule or a reason to combine unrelated changes into one release.

### Wave 1 — protect recovery and make failures measurable

**P1 · Backup headroom and recovery verification · S–M**

Source: `scripts/backup_database.py:25,192,254`, `scripts/monitor_operations.py:43`; [database findings 1 and 7](database.md).

- Make archive, source-DB, total-store, free-space reserve and restore-size limits one validated configuration. Budget seven completed archives, the protected verified archive when older, and space for the next in-progress archive.
- Expose timestamped current values, configured ceilings and remaining margin in Operations. Warn before a limit is reached; keep failed/unknown/stale states distinct.
- Extend snapshot inventory to audit operations/parts, schema history, price versions and administrator actions. Preserve compatibility with earlier checksummed manifests.
- Choose new bounds from actual disk reserve and restore measurements. Do not simply raise the archive constant while leaving the 2 GiB source/restore and 4 GiB store ceilings unchanged.
- **Gate:** create and restore a representative larger archive in isolation, validate inventory/application invariants and bounded CPU/memory/time, retain the last verified archive, and prove oversized failure does not delete protected copies. Reverting limits must not delete newly valid larger archives.

**P1 · Correct response lifecycle · S–M**

Source: `gateway/GatewayService.java:76,97–104`, `web/GatewayController.java:53–57`; [backend finding 1](backend.md).

- Model headers prepared, HTTP response committed, bytes forwarded and terminal audit state separately. Keep upstream status distinct from gateway/client outcome.
- Before commitment, upstream or audit failure must return an appropriate 502/503. After commitment, preserve incomplete/disconnected semantics and the exact forwarded prefix.
- **Gate:** isolated upstream headers-then-failure, partial frame, failure after a forwarded frame, pre-forward audit failure and nonstream write failure. Assert HTTP status, ledger result, capture coverage and retry semantics independently. No provider call is required.
- Keep this a separate compatibility-preserving fix with an immediately reversible app image.

**P1 · Phase telemetry and working-memory budget · M**

Source: `gateway/GatewayMetrics.java`, `audit/AuditCapture.java`, `messaging/KafkaAuditPublisher.java`, `limits/RequestBodyBudget.java`; [backend findings 2 and 3](backend.md).

- Add bounded histograms for admission/lock wait, request audit, upstream headers/first read, first local forward, encode/redact, publisher wait/ack, downstream write, terminal finalization and worker projection/commit. Record sample counts and release identity.
- Keep metric labels finite: operation/provider/result/route template, never member IDs, request IDs, raw paths or content. Client-observed latency belongs to browser/diagnostic measurements, not a server write timestamp.
- Reserve weighted memory before parsing/redaction and response accumulation. Account for retained observer/capture buffers and parse expansion, rather than only encoded Kafka bytes. Make concurrency and memory limits coherent.
- **Gate:** known-delay fixture attribution, bounded series count, and realistic maximum-size generated payloads under the deployed heap/container limits at 1/5/20 concurrent streams. Add near-capacity scenarios in isolation. Capacity rejection must remain bounded and observable, and audited when storage is available. Unavailable storage uses explicit rejection counters, never fabricated audit. No OOM or silent capture truncation.

**P1 · Accounting polling query · S, then M only if needed**

Source: `accounting/AccountingService.java:130`, `AccountingProjector.java:13`; [database finding 2](database.md).

- Verify a single latest-detail lookup per candidate instead of repeated correlated MAX work. Preserve pricing/classifier versions and manual overrides.
- Only introduce durable dirty-work tracking if query simplification is insufficient; keep a bounded reconciliation sweep for late FINISH/capture/usage changes. A PostgreSQL work marker may suffice; a second queue is not the default.
- **Gate:** old/new result equality in the same repeatable snapshot, including no capture, late capture, multiple detail versions with distinct timestamps, expiration, changed event counts and manual categories. Target at least 70% fewer buffer accesses on representative history without increased accounting lag. Measure, do not promise the preliminary timing ratio.
- Roll back the query immediately if results diverge; any new queue requires overlapping reconciliation until proven.

### Wave 2 — remove onboarding and investigation friction

**P1 · Native Windows setup and visible token flow · M–L plus independent S–M UI fix**

Sources: `frontend/setup/setup-claude.py`, `shared/ConnectionSetup.tsx`, `portal/PortalApp.tsx:78–118`; [UI findings 1 and 2](ui.md).

- Add an explicit OS/environment choice with custom controls: native Windows PowerShell, macOS/Linux, WSL. Do not rely solely on browser OS detection because setup may run on a remote machine.
- Implement Windows settings merge, hidden prompt, restricted ACLs, locking, atomic replacement, first-install backup, rotation and conditional undo. Preserve saved personal credentials and unrelated settings. Avoid execution-policy changes or privilege escalation as default setup steps.
- Keep a persistent ordinary `claude` workflow; retain optional separate profiles where supported. The current Python installer is Unix-specific and must not be relabeled as Windows-compatible.
- Place the one-time member token next to its issuance action, focus/announce it, and show the sequence appropriate to pending/approved/active state. Never embed the secret into a command, URL, analytics event or browser storage merely to save a click.
- **Gate:** Windows CI using temporary user directories, actual native PowerShell smoke, separate WSL validation, and setup/reinstall/rotation/undo/failure tests. Keyboard and 390px viewport verification for new-token visibility. Dashboard access on Windows is separate from terminal-installer support.
- Claude's [gateway configuration](https://code.claude.com/docs/en/llm-gateway-connect) documents `%USERPROFILE%\.claude\settings.json`; our installer still needs its own portability and safety tests.

**P1 · Explicit search scope and connected debugging · M**

Sources: `admin/RequestExplorer.tsx:26–75`, `admin/search.ts`, `admin/Metrics.tsx`, `admin/Management.tsx`; [UI findings 3 and 4](ui.md).

- Show active date/filter chips and a genuine custom-window selection. Keep URL state stable across refresh, Back/Forward, paging and detail close.
- Distinguish filtered request results from workspace aggregates; auxiliary queries must use the same scope or be visibly labeled as different.
- Make exact-ID searches explain date-bound misses and expired/unavailable data. Do not silently search unlimited history.
- Add copyable correlation IDs and one-click member/account/key, error cohort and access-attempt links. A rejected attempt without a captured request must say so.
- **Gate:** every drilldown carries exact interval/provider/model/member/state parameters; empty/expired/rejected cases are distinguishable; returning from detail preserves investigation context. Use local synthetic fixtures without adding production test rows.

**P1/P2 · Typed inspector and smaller reads · M**

Sources: `persistence/JdbcRequestQuery.java:63–72`, `audit/JdbcAuditStore.java:61`, `admin/RequestExplorer.tsx:80–94`; [UI findings 5 and 9](ui.md), [backend cross-review](backend.md).

- Add explicit summary, headers, body and events contracts. Summary includes outcome, identity, timings, capture/usage coverage, observation timestamp and revision/completeness state; it transfers no captured body or header data.
- Then switch UI tabs to demand-loaded authenticated endpoints with cancellation. Keep detail/export access, paged events, redaction and retention semantics intact, including compatible MCP behavior.
- Select only displayed metadata for the access list. Load ancillary Explorer analytics/access panels when requested rather than on every initial search.
- **Gate:** network assertions that Summary contains no body/header fields; large local capture fixture; bounded rendering; 401/403/expired/missing behavior; canceled requests do not replace newer state; exports remain equivalent. Mixed old/new UI clients must work during rollout.

### Wave 3 — purposeful graphs, Admin controls and accessibility

**P2 · Graph investigation and quota context · M**

- Add exact interval/model drilldowns, date/timezone labels, sample counts for percentiles and selectable/non-color-differentiated series. Preserve gaps/unknowns and accessible data tables.
- Keep all-model weekly, Fable weekly and session windows independent. Preserve the distinction between provider-reported utilization and estimated member shares; outside-platform usage remains unknown.
- Add source-timestamped backup/storage/worker headroom and latency phases where they answer an operational question. Do not treat stale data as healthy or populate graphs with test model requests.
- **Gate:** seven-series, sparse, one-sample, zero-sample, missing-quota and expired-window fixtures; identical chart/table values and keyboard drilldown scope.

**P2 · Joined Admin identity/actions and keyboard navigation · M**

- Link account, member/key, limits, sessions, requests and administrator actions in one investigation flow. Show action impact and distinguish key revocation from browser-session revocation.
- Keep the existing sole-admin model; role delegation is a separate product decision. Add pagination where bounded lists would otherwise hide records, not an unbounded fetch.
- Restore focus to Inspect after detail close and announce/focus page headings on route changes. Preserve the existing working custom-select keyboard behavior.
- **Gate:** keyboard-only route/filter/detail/paging flow and screen-reader smoke; appropriate controls per access state; action scopes and server authorization unchanged.

**P2 · Reduce dashboard authentication write amplification · S–M**

- Measure after reducing frontend fanout. Combine session/account reads where appropriate and conditionally coalesce `last_seen` touches within an explicitly defined idle-expiry policy.
- **Gate:** reduced writes per session without accepting revoked sessions, stale roles or incorrect absolute/idle expiry. Do not hide this behind a generic auth cache or silently loosen revocation guarantees.

### Wave 4 — storage and throughput work only after its gates pass

| Candidate | Why it may help | Required gate / reason to defer |
| --- | --- | --- |
| Lossless captured-body storage | Incoming bodies dominate current stored bytes | Restore-clone compression/retrieval measurements; TOAST already compresses text; exact authorized retrieval/redaction, late writes and rollback path must survive |
| Compact/partition replay identities | Audit operation identities grow outside capture retention | Define old backup, broker replay and delayed multipart horizons; no unconditional tombstone deletion or resurrection of expired captures |
| Event indexes and time pruning | Indexes are 54% of event relation size | Compare actual plans and sequence pagination across day boundaries; preserve uniqueness; zero index scans is not permission to drop a constraint |
| Consolidated metrics queries | Current report issues six related queries | Same-snapshot equality, missing/latest detail semantics, correct exact percentiles; never average bucket percentiles |
| Kafka linger/frame batching | Combined acknowledgement waits are measurable | Compare linger 0/1/5 and bounded batches at 1/5/20 paced streams; first-forward/inter-frame latency, lag and CPU must meet agreed budgets, with replay integrity unchanged |
| Single-part worker fast path | Common operations perform multipart storage steps and redundant codec work | Crash/replay/checksum/identity/poison-message/retention tests; benchmark under existing 0.5 CPU/768 MiB limits; current lag does not justify replicas |
| Canonical nonstream body | Avoid duplicated EVENT/FINISH serialization | Low current storage benefit; requires versioned wire/projection compatibility and identical capture/export behavior |
| Fewer admission round trips | One global transaction intentionally serializes exact limits | Measure lock wait first, retain cross-color exact RPM/concurrency and live permit ownership; do not replace with per-process limits |
| Explicit slow-client deadlines | Upload/read stalls may retain resources beyond provider timeout | Raw-socket tests with existing proxy/container limits; cancellation, bounded drain and correct audit finalization before selecting idle/deadline policy |

For index/compression experiments, use the exact deployed PostgreSQL/Timescale version in isolation. The current migration runner wraps changes in a transaction; do not insert an incompatible online-index statement into it. Hypertable operations need their own supported migration strategy. No destructive production index, retention or identity change is authorized by this review alone.

## When another Coder workspace would help

Adding a workspace is now an available option. It is not the next action from these measurements.

| Measured remaining problem or chosen objective | Candidate placement | What must be checked |
| --- | --- | --- |
| Backups/restore drills contend with live CPU or I/O | Move isolated restore verification and an encrypted recovery copy first | Private transfer, independent persistent storage/lifecycle, restore provenance, capacity and failure isolation; another workspace on the same node/storage is not automatically disaster recovery |
| Sustained gateway CPU/memory pressure after budgets and bounded concurrency fixes | Dedicated application workspace retaining a tested edge/cutover path | Two-color peak budget, shared admission correctness, DB/Kafka round-trip increase, secrets scope, measured latency under load |
| Worker projection is the demonstrated bottleneck | Improve single worker quota/query path first; consider moving broker and worker together only if still constrained | One topic partition cannot scale by simply adding consumers; preserve ordering, ack, replay and durable volumes |
| Database CPU/I/O remains saturated after query/storage work | Dedicated database placement | Network RTT, private authenticated transport, backup/restore rehearsal, staged replication/cutover strategy, data reconciliation and rollback; this is an architecture change |
| Independent detection of workspace loss is wanted | Observer on another available workspace | Actual independent failure domain and heartbeat; dashboard/journal first. External messaging destination needs explicit configuration and authorization |

Proposed investigation triggers, **not current incidents or universal capacity limits**: sustained host/container CPU above 70% or persistent throttling for 15 minutes; post-GC memory pressure approaching 80% of usable heap with rising pauses; nonzero pool pending and elevated acquire time correlated with user latency; growing worker lag/oldest-record age across several observation windows; or backup/restore resource contention. Tune thresholds against the measured workload and SLO. Observe representative busy periods and a controlled 20-client fixture before deciding a move solved a problem.

A second workspace does not fix hard archive ceilings, repeated queries, unbudgeted response buffers or the current public hostname's lifecycle. Cloudflare documents Quick Tunnels as temporary, with no uptime guarantee, changing hostnames and no SSE support. Successful streaming observations in [our diagnostics](../../streaming-diagnostics.md) are narrower evidence and do not change that support boundary. A stable supported ingress remains a separate future decision within the user's budget/domain constraints; no BugPolice hostname or tunnel will be repurposed. [Cloudflare Quick Tunnel limitations](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)

## Rollout, regression and recovery rules

1. Keep each wave in small independently reviewable PRs. Capture a baseline before changing a performance-sensitive path; define success and rollback conditions before experiments.
2. Run backend/installer/frontend checks plus relevant replay, authorization, PostgreSQL coordination and streaming failure tests. Keep generated fixtures isolated; production charts and history remain real data only.
3. Use additive DTO/schema changes. For new audit operations, deploy a reader compatible with both versions before enabling new writers; keep old records replayable. Do not roll a worker back to a version that cannot read already acknowledged new records.
4. Release app changes through the existing pinned-image blue/green path. Preserve inflight streams; no forced draining, hidden unsettled reset, offset skipping or drop-and-rebuild database migration.
5. Verify active SHA, stable state, gateway/audit readiness, bounded lag, role invariants and exact inspector/export results. Check both public-client and local phase measures without confusing their clocks.
6. Roll back app/query/feature changes when correctness, agreed p95, memory or lag gates fail. Retain data and compatibility adapters. A schema/data compaction rollback needs a separate tested plan before it can run.
7. Keep hourly recovery bounded to proven operational repair. This council does not recommend an unrestricted AI loop editing and deploying production code after arbitrary failures.
8. Upgrade deprecated CI action versions and pin the intended runner/runtime as a separate maintenance PR; the successful release currently emits Node-action/setup-java deprecation notices. Verify the pipeline after migration rather than coupling it to a database or protocol change.

## Recommended next batch

Start with three small tracks: **coherent backup budgets + restore inventory/headroom**, **response commitment correctness + phase metrics**, and **same-snapshot accounting query optimization**. In parallel, design the Windows installer and make the small token-visibility fix. Then ship the shared summary/body API contract and scoped request-investigation UI before adding more charts.

No additional information is needed to begin those bounded implementation tracks. Provisioning a database workspace, changing replay/retention policy, arranging a stable hostname, or selecting an external alert destination would be made concrete and reviewed as separate decisions if the evidence calls for them.
