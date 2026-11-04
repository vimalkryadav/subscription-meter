# Single-host blue-green delivery

This package is a deployment foundation, not evidence that production has been switched. The existing Quick Tunnel and public-edge container must remain running. Never run `docker compose down`, recreate `public-edge`, restart the tunnel, prune shared Docker resources, or modify BugPolice.

## Release invariants

- Only `app-blue` and `app-green` are replaced by normal releases. PostgreSQL, Kafka, audit writer, Prometheus, Caddy and cloudflared have independent lifecycles.
- The two colors use the same database, secrets and sessions. Admission and auth throttles serialize through separate PostgreSQL lock rows. A boot-unique instance lease expires after 30 seconds; renewal occurs every 5 seconds, with a conservative local 25-second deadline. Expired processes cannot resurrect their identities. Legacy NULL-owner requests are never automatically recovered.
- Caddy continues listening on port8182. Private application ports are loopback8183/8184. `/internal/deployment` is omitted from the public route allowlist and also requires a dedicated deployment token. The user-facing admin token/cookie cannot control deployments.
- Admission draining covers the complete forwarding and audit-finalization block. Any failed finalization increments `unsettled`, blocks readiness and prevents automatic old-container shutdown. Reconcile failures manually; there is deliberately no “clear failures” API.
- New request/response audit events must be durably acknowledged before the configured strict publisher forwards them. Audit writer lag does not imply capture loss; broker reachability is checked by readiness but cannot guarantee the next publish succeeds.
- Database schema changes are serialized by `scripts/migrate.py`; serving applications use `spring.sql.init.mode=never`. Add immutable, ordered `deploy/migrations/VNNN__description.sql` files. Checksums are verified on every execution. Existing tables are adopted by idempotent V001; existing rows and roles are preserved. Optional Timescale conversion/policies use `--timescale`; existing retention policies are preserved. Further policy changes require a new migration, not startup environment changes.
- Keep N/N−1 schema/event/API compatibility. This first implementation has no automated schema compatibility certificate; every release needs review before selecting Deploy. Destructive migrations are excluded from the routine deployment path.
- SHA-namespaced assets are copied out of the actual image into the existing static mount and never overwritten. Older assets remain available for old browser pages. The runtime Caddyfile is written preserving its inode because the existing container bind-mounts one file.

## Mandatory first bootstrap (explicit admission pause)

The legacy app has local rate-limit state and global startup recovery. Simply starting a second copy is unsafe. The script intentionally refuses operation until `deploy/state/bootstrap.json` contains `{"verified":true}`. This flag records completed checks; it does not substitute for them. The bootstrap script creates it after successful handoff; never create it just to bypass the guard.

1. Make and restore-test a database backup. Record existing cloudflared PID, hostname, Caddy image/config/command, volumes and BugPolice resource usage. Confirm at least the measured headroom for a second1GiB app plus broker/worker, free disk and DB connection budget. Existing measured host:4CPUs, about15.8GiB RAM; preserve BugPolice headroom. No additional paid infrastructure is needed for this phase.
2. In an isolated Docker project test the pinned Caddy2.11.7 image with the exact command `caddy run --config /etc/caddy/Caddyfile --adapter caddyfile`, single-file read-only mount and admin disabled. Verify `SIGUSR1` reload, invalid-config retention, and a long SSE stream surviving both cutover and rollback. No `--resume` or API-mode config changes are allowed with this signal-only workflow. The local isolated fixture has now passed these checks, including byte-exact SSE across switch/rollback and a pause using the actual route allowlist. This does not certify Cloudflare or the live host.
3. Run the SQL migration preview, review the SQL and locks, then apply to the control-usage database only. Run `--timescale` only for the intended Timescale database. Migrations may lock existing tables; measure first on a restored dataset. Existing ownerless in-flight rows remain untouched.
4. The initial compatibility handoff needs an explicitly planned admission pause or a separately implemented legacy-state handoff. With an admission pause: stop new gateway and authentication work at the edge, wait for all old streams and audit writes, then wait at least60seconds from the last admitted request/auth attempt so local rolling budgets expire. Preserve old streams during this interval. This bootstrap is **not zero downtime** and must not happen silently. Do not claim a zero-downtime bootstrap until an alternative handoff has been implemented and tested.
5. Install a clean, dedicated Git checkout at `/home/coder/control-usage-deploy`. Its `.env` and `secrets` must reference the existing control-usage deployment’s private files without printing them. Its persistent `deploy/state` must survive checkouts. Run the updated `scripts/bootstrap.py` on the deployment checkout to create the missing random deployment token without printing or overwriting secrets. The protected parent directory remains0700; individual files remain readable by the unprivileged container user through secret mounts. Compose project remains `subscription-meter`, preserving the existing shared volume names. Do not repurpose the user's working checkout for automated `git checkout`.
6. Ensure the runtime files still mounted by Caddy remain under `/home/coder/subscription-meter`; receiver sets `CONTROL_USAGE_RUNTIME_ROOT` to that path. After reviewing and authorizing the initial pause, run `scripts/bootstrap_blue_green.py --sha FULL_SHA --image IMAGE_DIGEST --allow-admission-pause --caddy-sse-verified` from the dedicated checkout with `CONTROL_USAGE_RUNTIME_ROOT=/home/coder/subscription-meter CONTROL_USAGE_DOCKER_SUDO=1`. It applies additive migrations, stages blue/assets, validates readiness, installs a temporary new-admission503 with Retry-After, and waits for legacy active gauge plus ownerless pending rows to remain zero for60seconds. It then gracefully retires only the quiet legacy app while the pause remains active, before switching to blue. This prevents invisible body-reading/pre-ledger requests from carrying legacy admission into the new deployment; unfinished non-streaming HTTP requests may need retry during this explicitly authorized initial maintenance window. It writes release/bootstrap manifests. Before legacy retirement, failure attempts to restore the previous route. Once retirement is attempted it deliberately requires operator reconciliation instead of restoring a route to a possibly stopped or lease-unaware legacy process. Keep Caddy and cloudflared processes intact.
7. Install `deploy/prometheus.bluegreen.yml` as the scrape configuration in a separately scheduled observability change, retaining metrics secret protection. Scrapes have separate instance labels; stopped standby target being down is expected, not a gateway outage.
8. Configure GitHub production environment/branch restrictions and its `CODER_SESSION_TOKEN`. The release workflow runs only from main and starts via manual dispatch. Leave Deploy false until the bootstrap is verified. Ensure GHCR image visibility or a host read credential permits image pulls. No Coder or provider credential goes into a container image or public repository.

A sample manifest structure (replace placeholders before use):

```json
{
  "phase": "stable",
  "active": "blue",
  "slots": {
    "blue": {"sha": "FULL_GIT_SHA", "image": "ghcr.io/vimalkryadav/control-usage@sha256:IMAGE_DIGEST"}
  }
}
```

## Normal release and rollback

The GitHub workflow verifies Java/Python tests, publishes an immutable image and optionally uses the Coder CLI to invoke `receive_release.sh`. The receiver rejects a dirty deploy checkout, fetches trusted main, verifies ancestry and checks out the requested SHA. The deployment controller holds a host lock, verifies image revision metadata, applies additive migrations, installs immutable assets, starts the inactive color, waits for private readiness, validates Caddy configuration, signals reload and verifies the public-edge release marker. It drains the previous color and stops it only after active requests reach zero with no unsettled finalizations. Cloudflare URL verification and end-to-end streaming acceptance remain separate gates; a local release marker alone is insufficient evidence for tunnel streaming correctness.

For rollback use `python3 scripts/blue_green.py --rollback` from the configured deployment checkout with `CONTROL_USAGE_RUNTIME_ROOT=/home/coder/subscription-meter`. Review schema/event compatibility first. This resumes the retained previous release and directs new traffic there; the replaced release drains. It does not restore an old database backup or replay accepted upstream POST requests.

The controller intentionally fails closed when a prior deployment manifest is not stable. On an interrupted deployment inspect Caddy's actually loaded route, both private status endpoints, active streams and image digests. Reconcile the manifest to observed state before a new attempt. If drain times out or finalization fails, the old container stays running. Never force-stop it as an automatic cleanup action. A bad reload attempts to restore the saved previous config; failed restoration is an operator incident, not a reason to restart the tunnel.

## Required acceptance evidence before enabling automatic delivery

- Two instances concurrently enforce one member concurrency/RPM limit and shared authentication budgets; process death releases capacity only after lease expiry.
- Starting green never interrupts blue's live ledger/details. An expired owner is recovered, a legacy NULL owner is preserved, and a fenced process cannot renew.
- Login/logout/revocation/CSRF work across colors; `/manage` remains private; `/internal`, actuator, DB and broker are not publicly routed.
- Twenty concurrent SSE requests, including requests longer than30seconds, survive both route switch and rollback byte-for-byte. New traffic reaches the new release. Cancellation, timeouts and late ledger/audit failures behave correctly.
- Kill broker/worker/database in an isolated test: verify fail-closed publishing, bounded resource use, replay/idempotency and backlog recovery. Never restore mock mode in production.
- Test pipeline crashes before and after route switch; verify conservative manifest recovery and no automatic forced stream termination.
- Verify fingerprinted old assets load while new HTML/API are active; test failed readiness and malformed Caddy configuration preserve the current release.

Local Java tests use H2 for portable coordination behavior. `JAVA_HOME=... python3 scripts/tests/isolated_deployment.py` additionally runs isolated PostgreSQL18 migration/admission/auth/ownership tests and the exact pinned Caddy image with long-lived SSE switch/rollback, invalid reload and admission-pause checks. These passed locally, including twenty concurrent35-second SSE streams across reload and rollback. They do not certify Cloudflare streaming, production-host behavior or end-to-end twenty-user load; those acceptance gates remain.

Cloudflare Quick Tunnels have no uptime guarantee and document unsupported SSE plus a200-in-flight request limit. App blue-green provides deployment continuity on a healthy single host; it does not cover host, DB, Kafka, Caddy, tunnel or upstream failure. Sources: [Caddy signals](https://caddyserver.com/docs/command-line), [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [Coder setup action](https://github.com/coder/setup-action).

## Shared Kafka cohort and separate activation

All deployment commands load the base, blue-green and audit overlays. Profiles make the audit infrastructure opt-in; ordinary releases still issue `up --no-deps` for exactly one application color and never update the broker or worker. The first bootstrap explicitly records `audit.transport=jdbc`, overriding an accidental ambient Kafka setting. Coder's sudo wrapper preserves only the nonsecret image/release/audit-routing variables; database credentials remain in the existing private Compose environment file.

To enable Kafka later, separately provision the internal broker/topic, run the `audit-group-init` one-shot with profile `audit-bootstrap`, and start the pinned worker. Group initialization has no database/provider credentials and must refuse reinitializing an existing group or starting after a nonzero retained earliest offset. Broker/topic automatic deletion is disabled; the producer's backlog/log-size/free-space guard stops forwarding before the configured budget is exhausted. This avoids silently aging out acknowledged, unprojected events. Disk-pressure cleanup is an explicit reconciliation task, never automatic pruning.

After the Kafka acceptance gates and worker readiness pass, record a reviewed audit cohort in the existing release manifest:

```json
"audit": {
  "transport": "kafka",
  "workerImage": "ghcr.io/vimalkryadav/control-usage@sha256:IMAGE_DIGEST",
  "contractVersion": 1,
  "topic": "control-usage.audit.v1",
  "group": "control-usage-audit-v1",
  "partitions": 1
}
```

Normal deployment validates that the existing broker/worker containers run, the worker matches this exact image digest, and both worker and new gateway declare OCI audit contract1. New application colors receive the same topic/group/partition settings. The gateway's readiness additionally checks live audit safety. The worker image stays fixed across gateway releases. Upgrade a worker separately after checking event compatibility, applying migrations and verifying offsets/replay; update the manifest only to match the resulting running worker. Never change partition count while producers or retained replay data depend on the old mapping. Switching transport is an explicit operational change, not a side effect of a normal app release.

GitHub runs Java, Python deployment/quota tests and Node request-explorer tests before publishing. Coder SSH uses `--wait=no` and does not autostart the workspace. Before the first release, verify the production environment secret, GHCR pull access, clean dedicated checkout, private file links, exact image revision/contract labels and the Coder sudo policy permitting the explicitly preserved nonsecret variables. No pipeline or bootstrap command has been run against the live deployment as part of local preparation.
