# Database, storage and analytics council review

Reviewed commit `596d7f7d187d0e9eeb72cfeafb8f322bd8bce00f`, 2026-11-10, approximately 14:25–14:31 UTC. Review only. No production writes, migration, cleanup, vacuum, index change, backup generation, restore or provisioning. Production statements ran in READ ONLY transactions with 3–5 second statement timeouts. SELECT plans were inspected before two short comparative ANALYZE executions. Only aggregate metadata and sanitized plan metrics were returned; no captured payloads, account/session values or individual records were inspected or reported.

## Measurements and limits

| Measurement | Result |
|---|---:|
| Database size | 1,359,664,831 bytes / 1.266 GiB |
| PostgreSQL / Timescale | 18.6 / 2.31.0 |
| Hypertables | 4, each 8 daily chunks; compression disabled |
| Details / events / request-ledger physical size | 831,700,992 / 264,617,984 / 5,079,040 bytes |
| Access physical size | 5,300,224 bytes |
| Detail rows at later sample | 1,923 |
| Sum of stored request-body column sizes | 833,840,631 bytes |
| Sum of stored response-body column sizes | 70,558 bytes |
| Audit operation physical size / estimated live rows | 243,490,816 bytes / approximately 499,386 |
| Event index size | 141,836,288 bytes, 53.6% of event relation size |
| Latest completed archive | 526,465,997 bytes, 2026-11-10 14:02 UTC |
| Per-archive ceiling / remaining margin | 536,870,912 / 10,404,915 bytes (9.92 MiB, 1.94%) |
| Backup store used / configured ceiling | 1,516,620,759 bytes / 4 GiB |
| Backup filesystem / Docker filesystem free | 192,511,705,088 / 55,767,781,376 bytes |
| Last successful isolated restore | 2026-11-08 02:16 UTC |

Sizes are different short snapshots, not one transaction-wide balance sheet. Stored-column size is stored datum sizes, which may reflect TOAST compression, not raw prompt text or logical dump size; do not sum it with relation size. Physical-size deltas can include allocation and dead space. Table live/dead counts are estimates. Cumulative index statistics have an unknown observation epoch (`stats_reset` null), so they are not current rates. The session-local diagnostic timeout does not establish a production application timeout.

Cross-review evidence from backend/host owners: 30-minute gateway pool pending maximum 0, active maximum 1, mean connection acquisition 0.208 ms; worker lag currently 0 and maximum 369. Host has about 10.6 GiB available RAM; sampled database memory about 341 MiB under a 1 GiB container limit. These do not establish an active database resource bottleneck.

## Ranked findings

### 1. P1 — Current archive limit is almost exhausted; monitoring detects failure rather than impending exhaustion

**Evidence:** Latest archive consumes 98.06% of its 512 MiB limit. `scripts/backup_database.py:25` fixes that limit; `:192` also rejects a source database above 2 GiB and a store that cannot fit one full maximum archive. `scripts/monitor_operations.py:43` checks success state and age, not remaining capacity. The archive grew 40,533,280 bytes between 01:17 and 14:02 today. A naive continuation would consume the remaining margin in roughly 3.3 hours from the last archive, but that is a sensitivity estimate, not a prediction: workload, compression and recent cleanup change archive size. Current physical DB is already approximately 160.5 MB above the database size recorded for the latest backup; this does not prove the next dump exceeds its cap.

**Impact:** A healthy live service can stop producing fresh backups before disk is remotely full. Old successful backup status hides the approaching boundary. Daily schedule means the failure may first be observed at the next scheduled run.

**Recommendation:** Review archive, source-DB, store and restore-resource budgets together now; add sanitized headroom and growth checks with early warning before a backup attempt. Preserve the agreed seven completed archives plus protected latest verified archive. Account for temporarily keeping an eighth archive and potentially an older protected verified copy. Do not make the archive ceiling larger without enlarging/testing the combined store and restore budget.

**Effort / risk / acceptance:** Small–medium. No data migration required. Restore the new maximum representative archive in isolation, enforce bounded output/time and disk reserve, and demonstrate warnings below the ceiling and failure without deleting protected copies. Rollback can restore old configuration, but must not delete larger archives created under the new budget.

### 2. P1 — The accounting no-op poll repeatedly searches historical detail chunks

**Evidence:** `AccountingService.java:130` joins all completed requests to their latest details through a correlated MAX subquery; `AccountingProjector.java:13` repeats every five seconds. A single production EXPLAIN ANALYZE returned zero work after scanning 1,945 requests, performing 13,615 latest-detail subplan loops, and 97,185 shared-buffer hits: 167.086 ms execution plus 29.304 ms planning, zero disk reads. A read-only LATERAL latest-row candidate took 26.195 ms execution, 27.701 ms planning and 17,688 hits. That later snapshot returned one work item; therefore this is directional optimization evidence, not a same-snapshot correctness proof or established sixfold end-to-end gain.

**Impact:** Work grows with retained history even when no accounting updates are pending. Present pool metrics show no user-facing contention; future contention and extra CPU are risks, not observed outages.

**Recommendation:** First replace the repeated MAX lookup with one latest-row lookup per candidate and verify semantics. Then consider a durable dirty-request queue or completion/change marker, with a bounded reconciliation sweep for late captures, usage changes and classifier-version changes. Preserve manual categories and historical pricing versions.

**Effort / risk / acceptance:** Small for query rewrite, medium for durable work tracking. Compare both result sets in the same snapshot; test missing capture, late FINISH, changed event counts, multiple detail versions with distinct timestamps, expired details and manual overrides. Target at least 70% fewer buffer accesses on representative retained-history data, without increased reconciliation lag. Query-only rollback is immediate; queue rollout needs dual reconciliation until proven safe.

### 3. P1 — Incoming captured conversation bodies dominate storage growth

**Evidence:** Details occupy approximately 832 MB, versus only 5.1 MB for the request ledger. The later size-only aggregate found approximately 834 MB of stored request-body columns across 1,923 detail rows, versus only 70.6 KB of stored response-body columns. `JdbcAuditStore.java:13` persists the full redacted request body. `AuditCapture.java:150` intentionally avoids duplicating raw streaming SSE in response_body; streaming events are stored separately. All hypertables currently report compression disabled. These measurements do not prove cross-request duplicate content, and none was inspected.

**Impact:** Expanding request history/context can exhaust backup limits well before ledger row count appears large. Optimizing response-body duplication alone would not materially fix today's observed storage footprint.

**Recommendation:** Track per-day stored-byte growth and capture-size distribution separately from request counts. Evaluate lossless storage options on a restore clone: supported chunk compression and/or content-addressed compressed bodies behind the same retrieval interface. Keep accepted real history and capture semantics intact; no truncation, reclassification or record deletion is proposed. Do not equate unenabled Timescale compression with uncompressed text: PostgreSQL already uses TOAST storage/compression, and additional savings require measurement.

**Effort / risk / acceptance:** Medium–large. Establish actual compression and retrieval results, full-capture integrity checks, backup/restore round trips and late-write behavior before production migration. Preserve original bodies or retain a reversible read path during conversion. Acceptance is demonstrated space reduction with byte-equivalent authorized retrieval and unchanged redaction/completeness semantics.

### 4. P2 — Audit operation tombstones grow outside the hypertable retention policies

**Evidence:** `audit_operations` is a regular table, approximately 243.5 MB with about 499k estimated live rows and 118.2 MB indexes. `JdbcAuditProjector.java:32` creates a row for each operation, `:57` marks it complete, and `:108` intentionally keeps expiry tombstones. No automatic completed-operation deletion was found. `V003__audit_pipeline.sql:1` defines the regular table; the four Timescale policies do not govern it.

**Impact:** Long-term database and dump growth continues even after 30-day captures expire. This is intentional deduplication state, not disposable test data or proven accidental bloat.

**Recommendation:** Measure daily tombstone growth and define a replay-safe compaction horizon tied to durable Kafka low-watermarks, maximum replay/restore age and incomplete multipart operations. Consider compact tombstones, partitioning or smaller keys only after proving old operations cannot resurrect captures. An unconditional DELETE of completed rows is unsafe.

**Effort / risk / acceptance:** Medium–large, high correctness risk. Tests must replay duplicates across old/new boundaries, restore older snapshots and process delayed multipart envelopes without double application or resurrected bodies. Rollback of a schema-only trial is straightforward; deleted deduplication evidence is not recoverable without restore, so retain it during rollout.

### 5. P2 — Event indexes are over half the event storage; event lookups do not prune by the partition key

**Evidence:** Events occupy 264.6 MB, of which 141.8 MB are indexes. `schema.sql:39` defines `(request_id,sequence,observed_at)` primary key; `:41` adds `(request_id,sequence)`. The latter occupies approximately 57.8 MB across chunks and is actively selected. The primary-key indexes showed zero read scans in cumulative statistics but still enforce uniqueness and must not be removed on that evidence. `V001__hypertables.sql:12` partitions by observed_at, whereas `JdbcRequestQuery.java:72` filters request_id/request_started_at/sequence, and `AccountingService.java:114` reads events by request_id and type, without observed_at bounds.

**Impact:** Additional daily chunks increase planning/index probes; every event pays index write, physical-storage and restore-rebuild cost. There is no measured slow event read here.

**Recommendation:** On a restore clone, benchmark whether the PK alone can satisfy sequence pagination before considering removal of the narrower redundant-prefix index; its smaller size may make retaining it worthwhile. Carry validated event-time bounds to lookups when possible, preserving events crossing midnight and long-running requests. Keep uniqueness guarantees.

**Effort / risk / acceptance:** Medium. Demonstrate representative pagination and finish-manifest latency, fewer chunk probes, reduced total storage/WAL and unchanged ordering/uniqueness. Rebuild an index to roll back a removal. Existing migration runner wraps changes in one transaction (`scripts/migrate.py:27`), so any online index strategy needs an explicitly compatible migration path, not an unreviewed CONCURRENTLY statement inside that transaction.

### 6. P2 — Each metrics report repeats the same request/detail join six times

**Evidence:** `JdbcMetricsReader.java:20` defines a shared SQL string, not a shared materialized result; `:44`, `:54`, `:57`, `:60`, `:65`, `:69` submit six separate queries inside a repeatable-read transaction. Details join only on request ID, without a detail-time predicate. Current aggregate duplicate-detail-ID count is zero, so duplicate metrics are not demonstrated. Request range predicates do bound ledger reads. Percentiles are correctly computed from request samples rather than averaged bucket percentiles.

**Impact:** A report repeats data access and exact percentile sorting as window volume grows. This has not been measured as a current endpoint latency incident.

**Recommendation:** Instrument individual report queries first, then consolidate compatible aggregates or cache the complete immutable report briefly by window/filter. Use one canonical latest-detail relation where semantics require it. Retain exact percentile semantics or explicitly distinguish approximations; do not silently average percentiles. A continuous aggregate is optional only after measuring its value and its interaction with detail updates and retention.

**Effort / risk / acceptance:** Medium. Compare summary/bucket/error/top-member/top-model results on identical snapshots, including expired/missing details and multiple detail candidates. Establish query-count and buffer reduction on a representative 30/90-day fixture. Query/caching changes should be feature-gated for rollback.

### 7. P2 — Restore drills are valuable but their inventory omits audit recovery state and the latest larger archive is unverified

**Evidence:** `backup_database.py:28` checks eleven tables plus admin count and Timescale metadata. The actual dump is a full database dump, but count verification omits audit_operations, audit_payload_parts, price versions, admin action logs and schema history. Last verified archive is from November 8 and 113.4 MB, versus the latest 526.5 MB archive. This is within the documented weekly restore cadence, not an existing schedule failure. `docs/operations.md` explicitly notes database backups exclude Kafka records not yet projected.

**Impact:** Current successful drill status proves an older/smaller archive restored to the selected inventory, not full application recovery with pending events or the ability of the bounded restore container to handle the newest volume.

**Recommendation:** Include recovery-relevant row counts and migration/version invariants in the same exported snapshot; add application-level referential/completeness smoke checks to the isolated target. Rehearse the enlarged archive before accepting new backup budgets. Document recovery ordering for database and broker backlog. Retain the previous verified archive throughout.

**Effort / risk / acceptance:** Small–medium. No live-schema migration needed for inventory expansion. Restore into an isolated target, compare the complete recorded inventory and exercise sanitized request/accounting/audit invariants. Existing checksummed archive formats need versioned manifest handling so older archives remain usable. Off-host recovery copies are a separate infrastructure decision; current same-workspace archives do not protect against workspace loss.

### 8. P2 — Query attribution is insufficient for deciding whether a database split is worthwhile

**Evidence:** pg_stat_statements is not installed; shared_preload_libraries contains Timescale only, track_io_timing is off. Cumulative request-detail index scans approach 937 million, but without an observation epoch or query attribution they do not identify current load. The bounded accounting plan provides specific evidence; current connection/pool samples show no contention.

**Impact:** A workspace move could add network round trips to many small JDBC operations while leaving the known redundant queries and hard backup limits intact.

**Recommendation:** Add sanitized per-operation query duration/row/timeout counters and capacity trends. Evaluate statement-statistics instrumentation in a separately reviewed maintenance change, avoiding collection or logging of raw payload SQL. Capture representative database CPU, I/O, pool wait and request latency before any split decision.

**Effort / risk / acceptance:** Small–medium. Adding a preload extension may require restart and must preserve existing Timescale preload configuration; application timers can ship first. Acceptance is stable attribution for accounting, metrics, explorer and audit projection, plus a workload-based resource forecast. Revert instrumentation without data migration if overhead is material.

## Retention, real history and the workspace decision

The live database has daily scheduled 90-day requests and 30-day details/events/access retention. This matches existing policy, not an unauthorized conflict. Preserve real users and records inside that policy and preserve historical backups under their agreed policy. In particular, no member/test reclassification or cleanup is part of this review. Make the different ledger, capture and backup horizons visible to users; if longer interactive history is wanted, decide it explicitly and budget storage before altering retention. `JdbcRequestQuery.java:21` already distinguishes expired from unavailable captures.

Prioritize backup headroom and the measured accounting query. Then forecast body/tombstone growth, verify recovery at the new size and measure report/event-query behavior. The evidence does **not** justify provisioning a database workspace now. A separate workspace becomes defensible if sustained measured CPU/I/O/memory pressure remains after query/storage work, or if an explicitly chosen isolation/availability objective warrants it. It will not repair archive ceilings, query amplification or unbounded tombstones, and requires tested network, credential, backup, latency and rollback/cutover arrangements.
