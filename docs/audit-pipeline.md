# Acknowledged Kafka audit transport

The default transport remains JDBC. Set `METER_AUDIT_TRANSPORT=kafka` only after applying `deploy/migrations/V003__audit_pipeline.sql`, creating the topic, starting the worker, verifying replay, and benchmarking the deployed resource limits.

Gateway settings:

| Setting | Default |
| --- | --- |
| `METER_AUDIT_TRANSPORT` | `jdbc` |
| `METER_AUDIT_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `METER_AUDIT_KAFKA_TOPIC` | `control-usage.audit.v1` |
| `METER_AUDIT_KAFKA_PARTITIONS` | `1` |

The producer uses explicit fixed partition mapping by request ID. Every gateway color must use the same partition count. Change it only after draining all producers and catching up all consumer partitions. Provision the topic explicitly, with a maximum message size of at least 524288 bytes, adequate retention, and disk monitoring. The worker does not auto-create topics.

The Kafka overlay runs `audit-volume-init` before the broker: the same Kafka image changes only the dedicated volume's top directory to UID/GID1000 and mode0700. It runs as root with only `CHOWN`/`FOWNER`, a read-only root filesystem, no network, and no secret/host-directory mounts. It never recursively changes existing log files. This initializes a fresh Docker named volume that may otherwise be root-owned and unwritable by the Kafka user.

When manually starting the broker with `--no-deps`, initialize its new volume explicitly first, because that flag skips the dependency:

```sh
docker compose --profile audit -f compose.yaml -f compose.audit.yaml \
  run --rm --no-deps audit-volume-init
```

For recovery from a failed first start, stop only the new `audit-kafka` service before this step, then start it again. Keep the existing gateway, database, edge, tunnel and other workspace services running. Inspect unexpected ownership of existing payload files separately; this initializer deliberately does not repair their permissions.

Launch the independent worker from the packaged executable jar:

```sh
java -Dloader.main=dev.subscriptionmeter.messaging.AuditWorkerMain \
  -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

The worker requires `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and `METER_AUDIT_KAFKA_BOOTSTRAP_SERVERS`. It accepts the topic setting above and `METER_AUDIT_KAFKA_GROUP` (default `control-usage-audit-v1`). Give it no upstream provider credential. Its datasource account needs only the projection tables and audit inbox tables; schema migration runs separately. `/health/readiness` and `/metrics` bind to `127.0.0.1:9091` by default; `METER_AUDIT_WORKER_BIND` and `METER_AUDIT_WORKER_PORT` override this for a private container network. Never route this port through the public tunnel.

## Delivery contract

Request audit publication is acknowledged before upstream submission. In Kafka mode, complete SSE frames in each bounded input read are batched (maximum 64 frames), redacted for audit, acknowledged, then the original response bytes are forwarded. Partial frames remain buffered. Nonstreaming bodies are bounded and acknowledged as a response-body event before forwarding. The existing JDBC transport retains its legacy forwarding behavior.

Bounds are explicit: at most 64KiB per forwarded read, 512KiB per SSE frame, and the configured response/event limits (defaults 8MiB/10000). Exceeding a strict bound stops further forwarding. It does not silently truncate the audit while continuing the response. These bounds mean **complete capture of admitted, bounded responses**, not unlimited capture.

Each operation has a stable identity, producer timestamp, checksum and numbered 192KiB payload parts. The worker transactionally stores parts, validates the assembled checksum, projects the operation and marks it complete. It deletes projected payload parts but retains operation identities for replay deduplication. Kafka offsets commit only after all database transactions in a polled batch commit. A crash after database commit and before offset commit replays safely. Completed-response manifests verify event count and sequence coverage.

Malformed records, identity conflicts, unsupported versions and database errors stop the worker without advancing offsets. A process supervisor should restart with backoff and alert; this version does not automatically quarantine/skip poison messages. Never log record values while investigating. Incomplete operations remain visible in `audit_operations` and `audit_payload_parts`.

Producer acknowledgement is not proof that a client received bytes. A one-broker deployment on the existing Coder VM does not protect against host/volume loss. Database projections and client delivery are separate states. Kafka mode journals terminal request state and the complete observed usage snapshot as a stable `RESULT` operation; the worker updates the request ledger in its deduplication transaction. Request admission still writes synchronously to the database, so a database outage can preserve terminal results from already admitted streams but prevents new admissions.

## Resource and operational limits

The producer keeps a 32MiB Kafka buffer and at most eight outgoing wire records per acknowledgement window. Up to 20 small publications can run concurrently; large operations have one additional permit. A conservative 128MiB serialization-memory budget is reserved before encoding, with a three-second admission limit. Encoding is bounded to 48MiB per operation and parts are generated lazily. Publication uses a 15-second overall acknowledgement deadline, plus at most one in-progress `send` blocking for its configured three-second limit; this is not a hard real-time guarantee. Java object/string copies and redaction add memory overhead beyond these byte limits; include concurrent request bodies and both gateway colors in heap sizing. No local disk fallback or unlimited retry queue exists. The worker projects batches of up to 16 records transactionally, using a two-connection Hikari pool.

The production guard requires a fresh successful probe: correct topic partitions/leaders, `cleanup.policy=delete`, automatic time/size retention disabled, initialized committed offsets inside the retained log range, at most 4096 unconsumed records, at least 2GiB broker filesystem headroom, and broker logs below a conservative 768MiB high-water mark. It permits at most 64MiB of estimated wire writes between probes. Guard failures stop new publications; no automatic offset reset or silent skipped backlog exists. These are conservative software limits, not a filesystem quota against unrelated host processes. Gateway metrics expose `meter.audit.guard.ready` and finite reason labels, publish acknowledgement latency/failures, and capture failure/truncation counters. Worker metrics expose records/bytes (including safe replays), lag, last poll and readiness. Worker readiness requires assigned partitions, a recent poll and a successful database probe.

Bootstrap a **new empty-log consumer group once**, after topic creation, using the worker entrypoint with `--bootstrap-group` (or the optional `audit-group-init` Compose service). Existing offsets and a nonzero retained log start are rejected. Worker `auto.offset.reset=none` turns missing/lost offsets into a stopped process requiring recovery.

Automatic time/size-based broker deletion is disabled so an unprojected backlog cannot age out. Each gateway now checks fresh broker size every five seconds on a separate maintenance thread. At 512 MiB it invokes the committed-offset pruner without the usual 24-hour history delay. This deletes only already-projected Kafka history; retained database captures are unchanged. Invalid/missing consumer watermarks abort cleanup. Reclamation is asynchronous and may not keep pace with a burst: the 768 MiB admission guard remains enforced. Metrics expose broker log bytes and cleanup outcomes. Run the worker entrypoint with `--prune-committed` as reviewed scheduled maintenance or manually: it retains 24 hours of Kafka history and deletes only records at or below the canonical worker's committed offsets. If the log budget is reached sooner, `--prune-committed-now` explicitly removes already projected records up to that same watermark without the history delay. Neither command deletes unprojected records or database audit history. They require only Kafka configuration, not database/provider credentials. Segment reclamation is asynchronous, so admission may remain blocked until Kafka frees eligible segments. If the worker cannot advance, fix projection first; pruning cannot recover space occupied by unprojected records. Do not use blanket topic deletion or offset resets as a capacity fix.

Keep deduplication identities at least as long as any supported replay horizon. Completed identities currently remain as durable tombstones; automatic tombstone deletion is deliberately absent. The worker expires incomplete payloads in bounded groups of 100 after the configured capture/metadata retention, preserves a `retention_expired` tombstone, and refuses to reconstruct expired bodies on delayed replay. No silent resurrection after retention deletion occurs. Consumer oldest-record-age alerts, dedicated storage monitoring, poison-message repair tooling and deployed resource/load verification remain release gates.

## Isolated tests

```sh
docker compose -p control-usage-test -f compose.audit-test.yaml up -d
AUDIT_INTEGRATION=true mvn -Dtest=AuditKafkaIntegrationTest test
mvn -Dtest=AuditPipelineTest,AuditCaptureTest test
docker compose -p control-usage-test -f compose.audit-test.yaml down -v
```

The integration fixture binds only localhost ports 29092 and 25432. It creates a unique topic and PostgreSQL schema, publishes a multi-part generated body plus stream event, capture completion and terminal usage result. It projects earlier operations, simulates an unreachable projection database for the already acknowledged terminal result, closes without committing Kafka offsets, then verifies replay restores usage and does not duplicate earlier projections. It also checks transaction rollback for unsupported operations. No real provider requests or production mock mode are involved.

## Scheduled committed-history maintenance

`deploy/control-usage-audit-maintenance.timer` invokes `scripts/maintain_audit.py`
hourly on the Coder host. The script requires a stable Kafka deployment and the
pinned running worker, serializes its own invocations, and runs only
`--prune-committed`: it keeps 24 hours of Kafka history and never advances beyond
the canonical worker's committed offsets. Database capture is unaffected. The
service has a 90-second timeout, requires no provider credential, and skips
unconfigured/JDBC deployments or a deployment currently switching. Install only
these named units; do not modify other workspace services. Disk/backlog guards
still apply if the queue reaches its capacity before the history window expires.

## Capacity rejection and incident recovery

An unavailable audit guard returns HTTP 503 with Retry-After before gateway admission, preventing new phantom pending requests while storage is unavailable. Audit failures racing the readiness check also carry 503 semantics before the response is committed. An already-started stream can only terminate as incomplete, not change its HTTP status. A failed terminal audit write still marks the process unsettled; no automatic reset hides it.

For an existing unsettled process, first switch traffic to a verified healthy release, drain the old process, confirm no active requests, and verify the worker is healthy with zero lag. Preserve an incident receipt and pending-request metadata. Mark only that drained instance's unfinished records interrupted (unknown outcome), retain payloads and partial usage, then stop it and verify lease-based recovery. This is operator-led recovery; the hourly scheduler never marks unknown requests successful or force-stops unsettled instances.
