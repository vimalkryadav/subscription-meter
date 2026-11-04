# Streaming diagnostics

The Operations page has an explicit **Test browser streaming** action. It sends
one authenticated, CSRF-protected POST to `/admin/operations/stream-probe` and
expects 16 small, ordered events spaced 200 ms apart. It measures first event,
completion and delivery span from the browser. Events delivered together are
reported as buffered; missing or malformed events and connection errors fail the
test. The browser cancels after 15 seconds or when the operator leaves the page.

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
