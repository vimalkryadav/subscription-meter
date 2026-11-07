# Streaming diagnostics

The Operations page has explicit **Test browser streaming** and **Test 35-second
stream** actions. Both send an authenticated, CSRF-protected POST to
`/admin/operations/stream-probe`. The default/`profile=quick` response has 16 small,
ordered events spaced 200 ms apart; `profile=deployment` has 71 events spaced
500 ms apart. No arbitrary duration or event count is accepted. It measures first event,
completion and delivery span from the browser. Events delivered together are
reported as buffered; missing or malformed events and connection errors fail the
test. The browser cancels after 15 seconds for the quick profile or 45 seconds for
the deployment profile, or when the operator leaves the page. Both profiles
share a 20-stream limit per application instance. The long probe keeps an existing
deployment drain reservation until completion/disconnect, just like the quick
probe. Neither button initiates a deployment. `X-Control-Usage-Release` identifies
the serving app release, allowing a cutover test to distinguish old and new streams.

The probe never calls Anthropic/OpenAI, consumes a member key, writes request
capture, or creates usage/accounting records. It is a fixed diagnostic protocol,
not mock provider mode. Requests are bounded to 20 concurrent probes per application
instance, with no public duration or payload controls. Existing administrator
session, role and CSRF protections apply. It tests the browser-to-platform path;
it cannot prove provider connectivity or guarantee continuous availability.

## Evidence collected on 2026-11-04 UTC

The earlier real generation experiment produced one completed browser request
and 19 browser `ERR_HTTP2_PROTOCOL_ERROR` failures. Only one request appeared in
both gateway admission and access records. Its origin duration was 1,995 ms, first
byte 1,925 ms, while the browser observed 18,585 ms to first byte. No matching Caddy
or application error explained the other 19 failures. Subsequently the remaining
19 authorized generations completed using an HTTP/1.1 client, with 4,573 ms client
p95 completion, all captured events matching, and all temporary member keys
revoked. This is 20 successful generations total, not proof that the original
20-way browser batch passed.

To separate tunnel transport from client protocol, a standalone loopback server
was exposed using two temporary Quick Tunnels. It streamed 16 fixed events over
about 3.2 seconds, never used a provider/database, accepted at most 300 streams, and
was limited to 20 concurrent streams. The production tunnel and application were
unchanged. Each row below is one 20-request batch:

| Client connection | cloudflared transport | Completed | First-byte p95 | Completion p95 | Event delivery span p95 |
|---|---|---:|---:|---:|---:|
| Local HTTP/1.1, no tunnel | None |20/20|524ms|3,723ms|3,005ms|
| Browser, negotiated HTTP/2 | QUIC |20/20|753ms|3,957ms|3,014ms|
| Browser, negotiated HTTP/2 | HTTP/2 |20/20|401ms|3,601ms|3,019ms|
| Terminal HTTP/1.1 | QUIC |20/20|1,094ms|4,293ms|3,005ms|
| Terminal HTTP/1.1 | HTTP/2 |20/20|841ms|4,045ms|3,010ms|

Every response had the complete ordered sequence and identical bytes within its
fixture. The origin saw peak concurrency 20 and no dropped/rejected fixture
streams. Both temporary tunnel processes and the diagnostic server were stopped.
These small samples show incremental delivery was observed; they do not establish
one transport is consistently faster or identify the earlier failure's cause.
Production transport was therefore left unchanged.

## Production diagnostic acceptance

After deploying the administrator probe in release
`374fec742fd3dcdee6940c6e7757a71901a2d18b`, all 20 concurrent probes through the
actual public URL completed over browser HTTP/2 with incremental event delivery.
First-event p95 was 118 ms, completion p95 was 3,120 ms, and delivery-span p95 was
3,005 ms. The dashboard button separately received all 16 events, with a first
event at 50 ms. These were fixed diagnostic streams, with no provider calls or
usage records. The earlier generation-specific browser failure remains
unexplained; this successful probe does not retroactively make that test pass.

## Reproducing in isolation

Use `scripts/tests/tunnel_probe.py` on an unused loopback port in a separate
temporary test directory. It exits automatically after 20 minutes by default.
Launch separate cloudflared processes pointing to that port, one with
`--protocol quic` and one with `--protocol http2`, each with its own local metrics
port. Do not restart or repoint the production tunnel. Open each diagnostic URL
in a new browser tab and execute `scripts/tests/tunnel_probe_browser.js` using
Playwright's authenticated page executor. The fixture has no accounts and uses
only a literal diagnostic header. The script refuses other page titles.

For the HTTP/1.1 comparison:

```sh
python3 scripts/tests/tunnel_probe_client.py https://TEMPORARY_HOST.trycloudflare.com
```

The optional `--mode ndjson` fixture changes only diagnostic response framing for
comparison. It is not a recommendation to disguise production SSE content types.
Save nonsecret JSON results outside tracked source, stop only the processes
created for the experiment, and close only its diagnostic tabs.

## Limits and source interpretation

Cloudflare continues to document SSE as unsupported on Quick Tunnels. Successful
observations do not change that support boundary. [Quick Tunnel limitations](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/).

`--protocol` changes cloudflared-to-Cloudflare transport, not the browser's HTTP
version. `http2Origin` separately changes the origin leg and requires HTTPS;
it should not be conflated with the existing HTTP loopback origin. [Run parameters](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/run-parameters/),
[origin parameters](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/origin-parameters/).

Caddy already flushes `text/event-stream` immediately, and our gateway route also
sets `flush_interval -1`. Adding more buffering settings is not supported by this
evidence. [Caddy reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy).

## Live cutover acceptance without provider requests

The repeatable browser harness is `scripts/tests/live_cutover_browser.js`. Install
it using Playwright's page executor on an authenticated administrator `/manage`
page, then call `window.controlUsageCutover.start(beforeSha, afterSha)` through
`page.evaluate`. Both values must be distinct, full release SHAs. Start it just
before an already authorized deployment begins; this harness never deploys or
rolls back anything. Both releases must support `profile=deployment`.

The harness opens ten 35-second streams on the old release, polls the public
index every 500 ms for at most 25 seconds, and opens ten more on the new release
once the public assets identify it. It requires all old streams to have received
their first event and still be open at that observation. Each stream must return
all 71 ordered events, valid completion flags, the expected release header and
at most 16 KiB. Individual requests time out after 45 seconds; the entire run is
bounded to 85 seconds. A missed cutover window fails the experiment rather than
claiming continuity. Do not navigate or reload the browser tab while it is running.

Read `window.controlUsageCutover.snapshot()` through `page.evaluate` until its
state is no longer `running`. Save that JSON in an ignored local artifact file,
then verify it independently:

```sh
python3 scripts/tests/verify_live_cutover.py artifacts/live-cutover-report.json
```

The verifier requires ten old streams that span the observed cutover and ten new
streams that begin after it, with all twenty overlapping. It rejects truncated,
buffered, misrouted, failed, duplicated or nonoverlapping results. The receipt
contains only release identifiers, status, counts and timings. Session and CSRF
values stay in the browser closure; no credentials or captured user content are
exported. This validates diagnostic traffic through the live browser/tunnel/edge
and application drain, not provider generation, audit persistence, every possible
network failure, or sustained uptime.

## Live rollback observation and deployment correction

The first live rollback rehearsal on 2026-11-05 delivered all 1,420 events across
20 incremental streams. Ten streams on release `d0eb6afb7cfe` spanned the observed
switch to `906036e9f5d6`, where ten more streams started. The independent receipt
verifier passed, with first-event p95 of 203 ms. Protocol metadata included HTTP/2
and unavailable entries; it does not prove every stream used HTTP/2.

The deployment command nevertheless failed: its one-shot edge check observed the
previous release immediately after SIGUSR1 and restored the prior route. This
was not a completed successful rollback. The manifest was conservatively left
in `switching`. On 2026-11-07, the serving release and saved prior configuration
were verified, the candidate was drained, and the manifest was reconciled to
stable on `d0eb6afb7cfe`. The deployer now waits for routing convergence and
verifies restoration before it cleans up a failed candidate. Fresh acceptance
of the corrected deployment is required; the earlier stream result alone does
not establish that the deployment state machine completed.
