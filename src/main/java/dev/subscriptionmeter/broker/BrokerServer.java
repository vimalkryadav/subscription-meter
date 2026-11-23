package dev.subscriptionmeter.broker;

import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * The only process that holds the real OpenAI key. Callers present virtual keys; every call is
 * checked (key, model, tools, rate, budgets), forwarded with the real key, and recorded.
 * Responses are passed through byte-for-byte, including server-sent events.
 */
public final class BrokerServer {
    private static final int MAX_JSON_RESPONSE = 32 * 1024 * 1024;
    private final BrokerStore store;
    private final Supplier<Optional<String>> realKey;
    private final Supplier<Optional<String>> adminToken;
    private final URI upstream;
    private final HttpClient http;
    private final java.time.Clock clock;
    private HttpServer server;

    public BrokerServer(BrokerStore store, Supplier<Optional<String>> realKey, Supplier<Optional<String>> adminToken,
                        String upstreamBase, java.time.Clock clock) {
        this.store = store; this.realKey = realKey; this.adminToken = adminToken; this.clock = clock;
        this.upstream = responsesUri(upstreamBase);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Accepts an OpenAI-style base with or without the trailing /v1 (e.g. OPENAI_BASE_URL). HTTPS required off-host. */
    static URI responsesUri(String base) {
        String trimmed = base.strip().replaceAll("/+$", "");
        URI uri = URI.create(trimmed.endsWith("/v1") ? trimmed + "/responses" : trimmed + "/v1/responses");
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("host.docker.internal") || !host.contains(".");
        if (uri.getRawQuery() != null || uri.getRawUserInfo() != null || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local)))
            throw new IllegalArgumentException("Upstream must be an https URL without credentials or query");
        return uri;
    }

    /** Host of the configured upstream, for display only. */
    public String upstreamHost() { return upstream.getHost(); }

    public InetSocketAddress start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/healthz", exchange -> safely(exchange, () -> health(exchange)));
        server.createContext("/v1/responses", exchange -> safely(exchange, () -> proxy(exchange)));
        server.createContext("/v1/models", exchange -> safely(exchange, () -> models(exchange)));
        server.createContext("/admin/", exchange -> safely(exchange, () -> admin(exchange)));
        server.createContext("/", exchange -> safely(exchange, () -> error(exchange, 404, "not_found", "Unknown route.")));
        server.start();
        return server.getAddress();
    }

    /** Stops accepting new calls and gives in-flight streams up to the grace period. */
    public void stop(int graceSeconds) { if (server != null) server.stop(graceSeconds); }

    interface Work { void run() throws Exception; }

    private void safely(HttpExchange exchange, Work work) {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            work.run();
        } catch (Exception e) {
            // Never echo exception text: it could include request content.
            System.err.println("Broker request failed: " + e.getClass().getSimpleName());
            try { error(exchange, 500, "broker_error", "The broker could not handle the request."); } catch (IOException ignored) { }
        }
    }

    static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = BrokerRequests.JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static void error(HttpExchange exchange, int status, String type, String message) throws IOException {
        json(exchange, status, Map.of("error", Map.of("type", type, "code", type, "message", message)));
    }

    private static String bearer(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).strip() : null;
    }

    private static byte[] body(HttpExchange exchange, int max) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(max + 1);
        return bytes.length > max ? null : bytes;
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200, Map.of("ok", true, "realKeyConfigured", realKey.get().isPresent()));
    }

    private void models(HttpExchange exchange) throws IOException {
        var key = store.authenticate(bearer(exchange), clock.instant());
        if (key.isEmpty()) { error(exchange, 401, "broker_invalid_key", "Invalid, revoked or expired key."); return; }
        var data = new ArrayList<Map<String, Object>>();
        for (var price : store.state(clock.instant(), false).get("prices") instanceof List<?> l ? l : List.of()) {
            String model = String.valueOf(((Map<?, ?>) price).get("model"));
            if (key.get().allows(model)) data.add(Map.of("id", model, "object", "model", "owned_by", "openai"));
        }
        json(exchange, 200, Map.of("object", "list", "data", data));
    }

    private void proxy(HttpExchange exchange) throws Exception {
        Instant now = clock.instant();
        if (!"POST".equals(exchange.getRequestMethod()) || exchange.getRequestURI().getRawQuery() != null) {
            error(exchange, 405, "method_not_allowed", "Only POST /v1/responses is supported."); return;
        }
        var key = store.authenticate(bearer(exchange), now);
        if (key.isEmpty()) {
            store.deny(null, null, false, now, 401, "broker_invalid_key", "");
            error(exchange, 401, "broker_invalid_key", "Invalid, revoked or expired key."); return;
        }
        byte[] raw = body(exchange, BrokerRequests.MAX_BODY_BYTES);
        if (raw == null) { deny(exchange, store.deny(key.get(), null, false, now, 413, "broker_body_too_large", "Request body is too large."), 413); return; }
        BrokerRequests.Checked checked;
        try { checked = BrokerRequests.check(raw, key.get()); }
        catch (BrokerRequests.Invalid invalid) { deny(exchange, store.deny(key.get(), null, false, now, 400, invalid.type(), invalid.getMessage()), 400); return; }
        var price = store.price(checked.model());
        if (price.isEmpty()) {
            deny(exchange, store.deny(key.get(), checked.model(), checked.streaming(), now, 400, "broker_model_unpriced",
                    "Model " + checked.model() + " has no price configured, so its spending cannot be capped."), 400); return;
        }
        var secret = realKey.get();
        if (secret.isEmpty()) { deny(exchange, store.deny(key.get(), checked.model(), checked.streaming(), now, 503, "broker_not_configured", "The broker has no OpenAI key configured."), 503); return; }
        var admission = store.admit(key.get(), checked.model(), checked.streaming(), BrokerRequests.estimate(price.get(), checked), now);
        if (!admission.ok()) { deny(exchange, admission, 429); return; }
        forward(exchange, checked, price.get(), admission.callId(), secret.get());
    }

    private static void deny(HttpExchange exchange, BrokerStore.Admission denied, int status) throws IOException {
        error(exchange, status, denied.denial(), denied.message());
    }

    private void forward(HttpExchange exchange, BrokerRequests.Checked checked, BrokerStore.Price price, String callId, String secret) throws Exception {
        long started = System.nanoTime();
        var request = HttpRequest.newBuilder(upstream).timeout(Duration.ofMinutes(30))
                .header("Authorization", "Bearer " + secret).header("Content-Type", "application/json")
                .header("Accept", checked.streaming() ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(checked.body())).build();
        HttpResponse<InputStream> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (IOException e) {
            store.unknown(callId, null, elapsed(started), null, "upstream_unreachable");
            error(exchange, 502, "broker_upstream_unreachable", "OpenAI could not be reached."); return;
        }
        int status = response.statusCode();
        String upstreamId = response.headers().firstValue("x-request-id").map(v -> v.substring(0, Math.min(200, v.length()))).orElse(null);
        String contentType = response.headers().firstValue("content-type").orElse("application/json");
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        if (upstreamId != null) headers.set("x-request-id", upstreamId);
        headers.set("x-broker-call-id", callId);
        try (InputStream in = response.body()) {
            if (status != 200) {
                byte[] bytes = in.readNBytes(1024 * 1024);
                // OpenAI does not bill requests it rejects before generating.
                store.failed(callId, status, elapsed(started), upstreamId, "upstream_http_" + status);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                return;
            }
            if (contentType.startsWith("text/event-stream")) {
                var scanner = new BrokerRequests.StreamUsage();
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                byte[] buffer = new byte[8192];
                boolean clientGone = false;
                for (int n; (n = in.read(buffer)) != -1; ) {
                    scanner.accept(buffer, 0, n);
                    if (clientGone) continue; // keep reading so the final usage is still recorded
                    try { out.write(buffer, 0, n); out.flush(); } catch (IOException e) { clientGone = true; }
                }
                record(callId, status, scanner.usage(), price, started, upstreamId, clientGone ? "client_disconnected" : "stream_without_usage");
            } else {
                byte[] bytes = in.readNBytes(MAX_JSON_RESPONSE + 1);
                if (bytes.length > MAX_JSON_RESPONSE) {
                    store.unknown(callId, status, elapsed(started), upstreamId, "response_too_large");
                    error(exchange, 502, "broker_response_too_large", "The response was too large."); return;
                }
                record(callId, status, BrokerRequests.usageFromJson(bytes), price, started, upstreamId, "response_without_usage");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException e) {
            store.unknown(callId, status, elapsed(started), upstreamId, "stream_interrupted");
            throw e;
        }
    }

    private void record(String callId, int status, BrokerStore.Usage usage, BrokerStore.Price price, long started, String upstreamId, String missingReason) {
        if (usage != null) store.settle(callId, status, usage, BrokerStore.cost(price, usage), elapsed(started), upstreamId);
        else store.unknown(callId, status, elapsed(started), upstreamId, missingReason);
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    // ---- Administration (called only by the gateway's admin pages, with the broker admin token) ----

    private boolean adminAuthorized(HttpExchange exchange) {
        var expected = adminToken.get();
        String presented = bearer(exchange);
        return expected.isPresent() && presented != null
                && MessageDigest.isEqual(expected.get().getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String actor(HttpExchange exchange) {
        String actor = exchange.getRequestHeaders().getFirst("X-Broker-Actor");
        return actor != null && actor.matches("[A-Za-z0-9._:@-]{1,80}") ? actor : "admin";
    }

    private void admin(HttpExchange exchange) throws Exception {
        if (!adminAuthorized(exchange)) { error(exchange, 401, "broker_admin_unauthorized", "Administrator token required."); return; }
        String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
        var query = query(exchange.getRequestURI().getRawQuery());
        Instant now = clock.instant();
        try {
            if (method.equals("GET") && path.equals("/admin/state")) {
                var state = store.state(now, realKey.get().isPresent());
                state.put("upstreamHost", upstreamHost());
                json(exchange, 200, state); return;
            }
            if (method.equals("GET") && path.equals("/admin/calls")) {
                String keyId = query.getOrDefault("keyId", "");
                int limit = AdminInput.integer(query.getOrDefault("limit", "100"), 1, 500, "limit");
                if (keyId.length() > 36) throw new AdminInput.Bad("Invalid keyId");
                json(exchange, 200, store.calls(keyId, limit)); return;
            }
            if (method.equals("GET") && path.startsWith("/admin/keys/") && path.endsWith("/secret")) {
                String id = path.substring("/admin/keys/".length(), path.length() - "/secret".length());
                if (!id.matches("[0-9a-f-]{36}") || !store.exists(id)) { error(exchange, 404, "not_found", "Unknown key."); return; }
                var key = store.reveal(id);
                if (key.isEmpty()) { error(exchange, 409, "broker_key_not_viewable", "This key cannot be shown again; create a replacement."); return; }
                store.log(actor(exchange), "broker.key.reveal", id, Map.of());
                json(exchange, 200, Map.of("key", key.get())); return;
            }
            if (method.equals("GET") && path.equals("/admin/log")) { json(exchange, 200, store.adminLog(AdminInput.integer(query.getOrDefault("limit", "50"), 1, 200, "limit"))); return; }
            byte[] raw = body(exchange, 64 * 1024);
            if (raw == null) throw new AdminInput.Bad("Body too large");
            JsonNode body = raw.length == 0 ? BrokerRequests.JSON.createObjectNode() : BrokerRequests.JSON.readTree(raw);
            String actor = actor(exchange);
            if (method.equals("POST") && path.equals("/admin/keys")) {
                var in = AdminInput.key(body, true);
                var issued = store.create(in.name(), in.purpose(), in.memberId(), in.budget(), in.rpm(), in.models(), in.maxOutputTokens(), in.expiresAt(), actor);
                store.log(actor, "broker.key.create", issued.id(), in.describe());
                json(exchange, 201, Map.of("id", issued.id(), "key", issued.key(), "prefix", issued.prefix())); return;
            }
            if (method.equals("PATCH") && path.startsWith("/admin/keys/")) {
                String id = path.substring("/admin/keys/".length());
                if (!id.matches("[0-9a-f-]{36}")) throw new AdminInput.Bad("Invalid key id");
                var in = AdminInput.key(body, false);
                if (!store.update(id, in.name(), in.budget(), in.rpm(), in.models(), in.maxOutputTokens(), in.expiresAt(), in.active()))
                    { error(exchange, 404, "not_found", "Unknown key."); return; }
                store.log(actor, in.active() ? "broker.key.update" : "broker.key.revoke", id, in.describe());
                json(exchange, 200, Map.of("updated", true)); return;
            }
            if (method.equals("PUT") && path.equals("/admin/settings")) {
                BigDecimal cap = AdminInput.money(body.path("weeklyCapUsd"), true, "weeklyCapUsd");
                BigDecimal before = store.globalCap();
                store.setCap(cap, actor);
                store.log(actor, "broker.cap.update", "settings", Map.of("previousWeeklyCapUsd", before, "weeklyCapUsd", cap));
                json(exchange, 200, Map.of("updated", true)); return;
            }
            if (method.equals("PUT") && path.equals("/admin/prices")) {
                var price = AdminInput.price(body);
                store.setPrice(price, actor);
                store.log(actor, "broker.price.update", price.model(), Map.of("inputUsdPerMTok", price.input(), "cachedInputUsdPerMTok", price.cachedInput(), "outputUsdPerMTok", price.output()));
                json(exchange, 200, Map.of("updated", true)); return;
            }
            if (method.equals("DELETE") && path.startsWith("/admin/prices/")) {
                String model = path.substring("/admin/prices/".length());
                if (!model.matches("[A-Za-z0-9._:-]{1,100}")) throw new AdminInput.Bad("Invalid model");
                if (!store.deletePrice(model)) { error(exchange, 404, "not_found", "Unknown model."); return; }
                store.log(actor, "broker.price.delete", model, Map.of());
                json(exchange, 200, Map.of("deleted", true)); return;
            }
            error(exchange, 404, "not_found", "Unknown admin route.");
        } catch (AdminInput.Bad bad) {
            error(exchange, 400, "invalid_request", bad.getMessage());
        } catch (RuntimeException parse) {
            if (parse.getClass().getName().startsWith("tools.jackson")) error(exchange, 400, "invalid_request", "Body must be JSON.");
            else throw parse;
        }
    }

    private static Map<String, String> query(String raw) {
        var map = new HashMap<String, String>();
        if (raw == null) return map;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) map.put(java.net.URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return map;
    }
}
