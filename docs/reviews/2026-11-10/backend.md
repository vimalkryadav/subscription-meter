# Backend architecture, latency and reliability council review

Reviewed main `596d7f7d187d0e9eeb72cfeafb8f322bd8bce00f`, read only. No code changes, provider calls, load tests, database mutations, restarts or infrastructure changes. Read `docs/audit-pipeline.md` and `docs/operations.md`; the recently deployed LOG_BUDGET/503 handling and bounded recovery remain part of the baseline.

## Evidence and current capacity

Bounded, aggregate Prometheus reads from the existing host, 30-minute lookback during review:

| Metric | Observation |
|---|---:|
| Acknowledged publication mean | 18.35 ms |
| Acknowledged publication observed rolling maximum | 386.90 ms |
| Acknowledged publications (Prometheus extrapolated increase) | 3,905 |
| Recent five-minute publication mean | 29.32 ms |
| Request mean duration / finished request observations | 16.52 s / approximately 122.7 |
| Maximum active requests | 2 |
| Hikari pending maximum / active maximum | 0 / 1 |
| Hikari acquisition mean | 0.208 ms |
| Gateway heap used / max | 292,595,672 / 777,912,320 bytes |
| GC pause total | 1.16 s / 30 minutes |
| Worker readiness / current lag / observed lag maximum | 1 / 0 / 369 records |
| Worker projected records / wire bytes | approximately 44,419 / 288.6 MB |
| Audit guard ready / broker logs | 1 / approximately 219 MB |
| Audit and request duration histogram buckets | absent |

The 30-minute interval may include release transitions; it is neither a controlled benchmark nor a p95/p99 measurement. Publication count includes different operation kinds, and worker wire-record count differs from publication count. Do not multiply their ratio by a latency average to claim per-request overhead. No production latency attributable to Hikari contention, heap exhaustion or the 80-request ceiling was demonstrated. Existing generated-stream test covers 1/5/20 streams and byte/usage integrity but is not the deployed full servlet/admission path (`src/test/java/dev/subscriptionmeter/AuditStreamLoadTest.java:68`). It uses an in-memory limiter and no real slow clients.

The DB council independently measured the periodic accounting no-op at approximately 167 ms execution + 29 ms planning, 97,185 buffer hits and 13,615 repeated latest-detail subplans while returning zero rows. That is stronger evidence of current avoidable work than hypothetical admission-lock contention; fix that before enlarging pools or moving services. Host council saw substantial free RAM and disk, with container CPU limits still needing scrutiny. There is no backend evidence that a second workspace is currently necessary.

## Ranked findings

### 1. Distinguish preparing response headers from committing the response (correctness)

**Evidence:** `src/main/java/dev/subscriptionmeter/gateway/GatewayService.java:76` sets `responseStarted=true` immediately after `sink.start`. The servlet sink only calls `setStatus`/`setHeader` (`src/main/java/dev/subscriptionmeter/web/GatewayController.java:53`); its first commit occurs later on write/flush at line 57. A read failure before the first complete acknowledged SSE frame therefore enters the IOException branch at GatewayService lines 97-100 with `responseStarted=true`, records partial, and can return the upstream 200 with an empty body. A runtime audit failure before any bytes have actually committed can propagate a 503 but retains upstream `status` in the ledger because line 103 guards its assignment on the same boolean. This is a source-proven distinction; no incident reproduction was run.

**Consequence:** A pre-delivery failure can look like successful HTTP delivery, or HTTP and ledger status can disagree. This impairs client retry behavior and the usefulness of audit failure dashboards.

**Change:** Give `ResponseSink` explicit commit/forwarding state, or separate headers-prepared, bytes-forwarded and servlet-committed state in a small request lifecycle object. Preserve upstream status separately from actual gateway/client status. Before commit return the appropriate 502/503; after commit retain incomplete/disconnected semantics. Do not claim an acknowledgement proves receipt.

**Acceptance:** Fake upstream returns headers then fails before data, fails with a partial frame, and fails after a successfully forwarded frame; inject an audit failure before first forward. Assert actual HTTP status, ledger status, capture state and byte prefix independently. Include nonstreaming write failure, which currently takes a different catch branch than per-read SSE writes.

**Effort/risk:** Small-medium / medium. Make this an isolated correctness change, preserving existing pre-admission 503 and Retry-After behavior.

### 2. Bound response and redaction working memory across admitted requests (capacity safety)

**Evidence:** Admission allows 80 active requests (`limits/PostgresRequestLimiter.java:43`). `audit/AuditCapture.java:17,47` retains a nonstreaming body up to the configured 8 MiB cap; `usage/AnthropicUsageObserver.java:25` independently retains the same bytes. `AuditCapture.java:89,149` creates further copies. Redaction constructs a JSON tree, recursively builds maps/lists and serializes again (`audit/CredentialRedactor.java:27-43`). The request budget only covers 64 MiB of raw request bytes (`limits/RequestBodyBudget.java:15`), and the 128 MiB publisher reservation begins after the already-redacted operation exists (`messaging/AuditMemoryBudget.java:8-22`, `messaging/KafkaAuditPublisher.java:33`). Java heap max observed is 777.9 MB. Eighty valid 8 MiB nonstreaming responses with two persistent copies alone are approximately 1.25 GiB. This is a bound mismatch, not an observed OOM; current active max was two.

**Consequence:** A legal burst can exhaust a single gateway heap, terminate in-flight requests and require reconciliation despite each request individually satisfying its size limit. Large request redaction can also peak before the publisher's large-operation permit is acquired.

**Change:** Introduce a weighted working-memory reservation before body parsing/redaction/response accumulation; account for response buffers and conservative parse expansion. Avoid two full-body holders by giving the usage observer the already-buffered nonstream body at EOF. Put published limits, per-process budgets and max concurrency into one validated configuration model. Reject capacity before upstream submission when possible; if a size limit is exceeded after submission, stop forwarding and preserve incomplete audit. Never truncate audit and continue.

**Acceptance:** In an isolated generated fixture with the deployed heap limit, mix near-limit ASCII/Unicode/deep JSON request bodies, 8 MiB nonstream responses, bounded maximum SSE frames and stalled readers. At budget capacity, receive bounded 503/rejections rather than OOM; admitted bytes and audit still match. Measure peak heap/RSS and GC, not only raw array sizes. Test both colors concurrently and explicit permit release after exceptions.

**Effort/risk:** Medium / medium-high. Start with budget instrumentation and conservative configurable reservations; broad shared-parser rewrites can follow later.

### 3. Add latency phases and usable percentiles before tuning (measured visibility gap)

**Evidence:** Only a combined publication timer exists (`messaging/KafkaAuditPublisher.java:32-47`); it includes memory wait, encoding, Kafka send and acknowledgement. `gateway/GatewayMetrics.java:24` records total request duration without histogram configuration. Production has no bucket series for either timer. `audit/AuditCapture.java:43` records `first_byte_ms` on upstream read before acknowledgement and client write; it is not time to first forwarded byte. Gateway duration starts after limiter admission (`GatewayService.java:42-48`) and ends before RESULT acknowledgement and filter-level ACCESS publication (`GatewayService.java:115-122`; `audit/GatewayAccessFilter.java:52`). Observed mean publication latency 18.35 ms and recent 29.32 ms justify attribution, not a claim that Kafka explains 16.52-second request duration.

**Consequence:** Optimizations can move latency between phases while dashboards appear improved. We cannot distinguish provider generation, audit acknowledgement, local encode work and a slow client, or compare p95 across releases. The current Metrics UI correctly explains that first response byte means upstream bytes, not generated tokens; this is an instrumentation gap, not a misleading existing UI label.

**Change:** Add finite-cardinality histograms for admission, request-audit ack, upstream headers, first forwarded byte, event redaction/encoding, publisher queue wait, broker ack, downstream write and terminal finalization. Expose batch events/bytes and response-working-memory utilization. Give worker projection/commit duration, last successful projection and oldest unprojected age. Keep labels limited to route/provider/operation/result; no request IDs, raw URLs, users or content.

**Acceptance:** Generated known-delay fixtures attribute delay to the expected phase; bounded metric series counts; report p50/p95/p99 over representative windows with sample counts and release labels. Retain the existing first upstream-byte metric under an unambiguous name.

**Effort/risk:** Small-medium / low. First implementation wave, concurrent with correctness fixes; no new observability service required.

### 4. Eliminate duplicate nonstream body serialization and projection without weakening capture

**Evidence:** `audit/AuditCapture.java:89-92` redacts/publishes a `response_body` EVENT before forwarding; lines 149-155 re-redact and put the same complete body in FINISH. `messaging/JdbcAuditProjector.java:71-74` persists EVENT data, while FINISH updates request detail. Thus nonstreaming bodies incur two redaction/encode/publication/projection/storage copies. This does not apply to SSE bodies, which correctly use events and an empty FINISH body. Subsequent DB council aggregate: 1,923 detail rows contain 833,840,631 request-body bytes (approximately795MiB) but only70,558 response-body bytes. Therefore duplication is negligible for current retained storage and this finding is deferred behind measured accounting and metadata API work; it is not the solution to current backup growth.

**Consequence:** Large nonstreaming responses consume avoidable CPU, Kafka budget, database space and backup capacity, and add another expensive terminal publication after the client body is sent.

**Change:** Use one acknowledged canonical body operation before forwarding. Project that operation into the required detail/body representation, while FINISH carries a manifest/reference and state, not the body again. Keep API/export compatibility through a projection or read adapter. Reuse a safely retained redacted representation only within its memory reservation. Rollout must allow old and new event schemas during blue/green replay.

**Acceptance:** Synthetic max-size response produces byte-identical forwarded output and identical redacted inspector/export output; only one canonical retained body. Crash/replay between body ack, forwarding and FINISH does not falsely mark complete or lose the body; old wire operations replay unchanged. Quantify wire/database byte and allocation reduction for nonstreaming samples.

**Effort/risk:** Medium / medium-high because it changes the durable schema/projection contract. Defer while nonstreaming volume remains negligible; the current storage issue is incoming context/request bodies.

### 5. Tune acknowledged batching and remove redundant terminal round trips, based on latency phases

**Evidence:** `GatewayService.java:78` reads 8 KiB. `AuditCapture.java:48-63` releases every batch of complete frames within that read, maximum 64. Publisher waits after eight wire records (`KafkaAuditPublisher.java:38-44`) and producer uses `linger.ms=5` (`KafkaAuditConfiguration.java:54`). BEGIN, HEADERS, FINISH, RESULT and ACCESS are separate synchronous publications; FINISH and RESULT are issued sequentially (`GatewayService.java:108-117`). Observed ack mean is 18.35 ms with max386.9 ms; no controlled comparison proves five-ms linger is dominant.

**Consequence:** Fine upstream chunks can introduce an acknowledgement wait per short group. The client can receive final bytes while the gateway still holds its admission/body permits through repeated terminal acknowledgements.

**Change:** First compare linger0/1/5 and bounded per-read/frame batching in the generated isolated fixture. Preserve `acks=all`, idempotence, request partition order, fixed memory/wire windows and acknowledgement-before-forwarding. A single typed terminal publication can contain distinct FINISH and RESULT operations in one acknowledged window while retaining each operation identity and projection semantics; do not claim Kafka sends across that window are atomic. Avoid a background unacknowledged forwarding queue. Increasing read buffer alone must not be sold as guaranteed coalescing of network reads.

**Acceptance:** At 1/5/20 concurrent paced streams, compare first-forward p95, inter-frame p95/p99, publication sizes, worker lag, CPU and allocations with exact byte/event/usage replay checks. Only accept a configuration that improves the target latency without regression beyond the agreed CPU/lag budget. Include low-rate streams so added batching delay is visible.

**Effort/risk:** Small for configuration experiment, medium for typed terminal seam / medium. No change until measured; do not increase partition count or weaken the strict contract as a shortcut.

### 6. Optimize the worker's common single-part projection before adding worker capacity

**Evidence:** Every batch re-encodes/re-decodes an envelope already decoded by the worker (`messaging/JdbcAuditProjector.java:28`; `AuditWorkerMain.java:62`). A common single-part operation does operation insert/lock, payload-part insert/read/count, ordered part read, projection, completion update and part delete (`JdbcAuditProjector.java:32-58`). The worker batches16 records but performs these statements per record in its transaction; CPU cap is0.5. Current lag is0, observed max369 against4096 guard, so overload is not established.

**Consequence:** High event rates amplify database round trips, transient payload writes and allocations. Lag then directly becomes an admission-availability concern even if upstream responses remain fast.

**Change:** Extract envelope validation from wire serialization; retain a single validation path without encode/decode copies. Benchmark a single-part fast path that verifies identity/checksum and applies the payload with durable tombstone inside the same transaction, avoiding temporary part storage for parts=1. Retain multipart assembly, poison-message fail-stop, exact replay deduplication and offset commit after transaction commit. Only then consider bounded prepared-statement batching or polling sizes.

**Acceptance:** Isolated crash after DB commit/before Kafka offset commit, same-ID conflicting payloads, duplicate records within one poll, corrupt checksums, partial multipart, retention expiry and every operation kind. Measure records/s, transaction p95, SQL statements/event and lag under the existing0.5CPU/768MiB limit; no production load test.

**Effort/risk:** Medium / high correctness sensitivity. Defer until new phase metrics or projected event volume shows worker cost warrants it. Do not add a second consumer when the topic has one partition.

### 7. Bound ingress and downstream blocking independently of the provider deadline

**Evidence:** Request-body reading occurs after raw-byte reservation but before shared member admission (`web/GatewayController.java:34-35,51`). A chunked unknown-length request reserves16MiB (`limits/RequestBodyBudget.java:18`), so four such uploads can reserve the64MiB budget. The upstream deadline only closes upstream `InputStream` (`upstream/JdkUpstreamTransport.java:41-43`, same shape in `provider/OpenAIPlanTransport.java:43`). Servlet writes/flushes are blocking (`GatewayController.java:56-57`). No explicit ingress/read/write deadline is set in project application or Caddy configuration. Container/proxy defaults may limit these; actual deployed slow-client behavior was not measured. The limiter intentionally keeps live permits until close or fencing (`limits/PostgresRequestLimiter.java:37-38`).

**Consequence:** Slow uploads or clients that stop reading can occupy working-memory/admission slots beyond the advertised provider timeout and obstruct drain. Closing the provider body does not by itself prove the client write is canceled.

**Change:** Establish explicit upload and downstream idle/write deadlines with cancellation propagated to upstream and bounded finalization. Bound pre-body request count as well as bytes, without changing how RPM is charged accidentally. Preserve strict permit ownership: never expire a live permit just to report spare capacity.

**Acceptance:** Isolated raw-socket clients trickle request bytes, stop reading an SSE response and disconnect at each phase. Verify bounded cancellation/drain time, settled or explicitly unsettled audit state, released memory/permits and no continued provider reads after cancellation. Record actual container/proxy limits before selecting values.

**Effort/risk:** Medium / medium-high; stream timeouts can interrupt legitimate users. Treat as a focused reliability test/fix, not a claim of a current outage.

### 8. Stop writing the same session row for every dashboard GET

**Evidence:** `portal/JdbcPortalRepository.java:34-40` does session SELECT then UPDATE for every authenticated call, and admin filter reads the account separately (`security/AdminAuthFilter.java:46-47`). Parallel dashboard requests with one cookie all update the same row. Current Hikari/DB samples show no wait, so this is definite write amplification with unproven user-facing contention.

**Consequence:** Dashboard refreshes and fanout create extra WAL, round trips and same-session serialization; the application pool is shared with gateway admission and lease work.

**Change:** Return valid session/account/role together and coalesce last_seen updates to a defined small interval using an atomic conditional touch. Keep revocation, absolute expiry, role checks and idle-time guarantees explicit; a naive cache must not turn revoked sessions into valid sessions. Exact current sliding expiry semantics versus a documented bounded touch interval is a product/security choice. Avoid generic authentication caching as the first change.

**Acceptance:** Concurrent dashboard reads reduce writes per session to the defined bound; revoked session and demoted role fail immediately under required semantics; absolute and idle expiry boundary tests, concurrent logout/login and blue/green requests remain correct. Compare endpoint p95 and DB statement count before/after.

**Effort/risk:** Small-medium / medium. Coordinate with frontend's measured dashboard fanout changes; implement one endpoint/data-fetch reduction before adding caches.

### 9. Shorten exact admission's serialized transaction; keep distributed correctness

**Evidence:** `limits/PostgresRequestLimiter.java:30-44` locks one global admission row, reads DB time/lease, prunes, counts RPM/member-active/global-active, then inserts. `GatewayService.java:48,58` separately inserts a request and updates its identity before BEGIN acknowledgement. The limiter is intentionally exact across colors. Production Hikari pending0, active max1 and acquire0.208ms show no present bottleneck.

**Consequence:** At higher request rates or higher DB round-trip latency, unrelated members queue behind the same lock. A second workspace farther from this DB could make this path slower while preserving the same global serialization.

**Change:** First instrument lock wait and transaction duration. Combine safe count/lease queries, move bounded old-row cleanup off the common path while preserving RPM semantics, and include provider/client fields in the initial request insert. Keep one correct transactional admission boundary until a real benchmark justifies per-member coordination plus a global capacity design. Do not replace it with per-process semaphores that oversubscribe during blue/green.

**Acceptance:** Existing cross-instance exact RPM/concurrency tests plus generated independent members at realistic burst rates and injected DB delays; compare admission p95 and statements/request. Crash/fence/recovery must not double-admit or reclaim live permits. No new workspace decision until this and current DB polling waste are measured after improvement.

**Effort/risk:** Small-medium for query consolidation / medium-high for lock design changes. Consolidation is a later optimization; retain exact global coordination now.

## Architecture decisions and deferred work

- Preserve the useful boundaries already present: `UpstreamTransport`, provider protocols, `RequestLedger`, `AuditPublisher`, replay projector and independent worker. No rewrite into reactive programming or additional services is justified by current measurements.
- Apply SOLID selectively: replace stringly typed terminal `Map<String,Object>` construction and duplicated catch/finally accounting with a typed lifecycle/terminal envelope; keep protocol observation separate from capture/replay and servlet commit state. Compatibility adapters should permit old and new wire records together. Refactor when implementing a concrete finding, not as a large formatting-only project.
- Keep deliberate safety properties: acknowledged request audit before upstream, acknowledged bounded response frames before forwarding, strict size limits, fail-closed guard, no offset skipping, tombstones, unknown-outcome reconciliation and blue/green leases.
- Defer partition changes, extra worker replicas, a second workspace, larger Hikari pools, persistent caching and new infrastructure. Current lag/heap/connection observations do not establish those needs. Fix measured accounting no-op work and near-ceiling backup growth identified by the other council tracks first.
- The new LOG_BUDGET recovery solves a known incident path; avoid presenting pressure pruning or hourly recovery as missing. Additional cleanup tuning must retain committed-offset proof and existing disk guard.

## Suggested waves

1. Correct commit/status lifecycle; add phase histograms and buffer budgets/telemetry; address measured accounting work and backup headroom in the other tracks.
2. Isolated generated experiments for paced audit streaming and legal maximum payloads. Choose the smallest winning configuration; coalesce dashboard fetch/auth work.
3. If metrics justify it, canonicalize nonstream body storage and optimize worker single-part projection with mixed-version replay/crash gates. Revisit infrastructure only with a measured remaining bottleneck.

## Cross-review: UI/API contract dependencies

- Agree strongly with UI findings5/9: a typed metadata-only request-summary endpoint and metadata-only access-list projection are preferable to eager full capture fetches. `persistence/JdbcRequestQuery.java:63-67` explicitly selects body/header fields for current detail, while `audit/JdbcAuditStore.java:61` uses SELECT* for access. UI measured98,082 decoded access bytes for50rows although five metadata columns render. This is a better immediate performance change than nonstream-body storage deduplication.
- Implement additive summary/body/header contracts first, then switch frontend tabs to demand-loading and cancellation, then retire unused wide reads after compatibility verification. Keep the existing export and detail paths compatible during deployment. Metadata summary should include retention/capture state, request/capture timestamps and a stable view/revision marker so a pending capture changing between tab loads is represented honestly. Do not return hidden body fields and rely on frontend omission for the claimed payload reduction.
- Preserve AdminAuthFilter on each new lazy endpoint; typed DTOs are an interface/maintainability improvement, not new authorization. Events remain sequence-paged and export remains incremental. Size-limit tests should use generated captures rather than pulling real large captures into test logs.
- Agree with explicit date/filter scope and links. Exact-ID lookup outside a selected time range should be an explicit user action or clearly explained alternate route; do not silently widen the entire cohort and leave the metrics/auxiliary tables in a different range.
- Portal GET fanout reduction precedes auth touch coalescing: fewer demand-loaded calls reduce existing auth writes without modifying session expiry semantics. Native Windows setup is a separately scoped user-facing correctness requirement; backend concurrency optimizations do not unblock it.
- UI browser timing includes the public tunnel and cannot be attributed to the Hikari/DB path. Keep synthetic browser client-visible first byte distinct from gateway first successful write and upstream first read; the server cannot prove network receipt merely by returning from write.
