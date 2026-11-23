package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.upstream.UpstreamTransport;
import java.io.*;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The routing engine for Claude Code's Anthropic requests. Deployments (Claude subscriptions, Codex logins,
 * and last of all the OpenAI API key) are filtered and ordered by {@link RoutingPolicy}; each attempt is
 * classified: transient errors are retried with backoff (and repeated failures cool a deployment down),
 * quota refusals block a deployment until its reset, context-length errors move on, and anything is only
 * retried elsewhere before the first byte reaches the client, so nothing is ever duplicated.
 */
@Component
public class FallbackRouter {
    public static final String MEMBER = "x-meter-member", SESSION = "x-meter-session";
    private static final Logger LOG = LoggerFactory.getLogger(FallbackRouter.class);
    private static final ExecutorService ATTEMPTS = Executors.newVirtualThreadPerTaskExecutor();

    /** Everything the engine consults, so tests can supply fakes. */
    interface Environment {
        List<Deployment> deployments();
        Double usedPercent(Deployment d, Instant now);
        boolean servesModel(Deployment d, String upstreamModel);
        /** Null means the primary Anthropic transport passed to {@link #route}. */
        UpstreamTransport anthropic(Deployment d);
        ResponsesTarget openai(Deployment d);
        void sleep(long millis) throws InterruptedException;
    }

    private final RoutingSettings settings;
    private final RoutingState state;
    private final DeploymentHealth health;
    private final Environment env;
    private final Supplier<Instant> clock;

    @Autowired
    public FallbackRouter(RoutingSettings settings, RoutingState state, DeploymentHealth health, DeploymentRegistry registry,
                          QuotaBalancer balancer, DeploymentClients clients, BrokerModels brokerModels) {
        this(settings, state, health, new Environment() {
            public List<Deployment> deployments() { return registry.all(); }
            public Double usedPercent(Deployment d, Instant now) { return balancer.usedPercent(d, now); }
            public boolean servesModel(Deployment d, String model) { return clients.servesModel(d, model, brokerModels); }
            public UpstreamTransport anthropic(Deployment d) { return clients.anthropic(d); }
            public ResponsesTarget openai(Deployment d) { return clients.openai(d); }
            public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        }, Instant::now);
    }

    FallbackRouter(RoutingSettings settings, RoutingState state, DeploymentHealth health, Environment env, Supplier<Instant> clock) {
        this.settings = settings; this.state = state; this.health = health; this.env = env; this.clock = clock;
    }

    /** A reply produced (or re-routed) by this router, carrying where it was actually served. */
    record Routed(int status, Map<String, List<String>> headers, InputStream body, String provider, String account, String model,
                  Closeable upstream) implements UpstreamTransport.Reply {
        public String routedProvider() { return provider; }
        public String routedAccount() { return account; }
        public String servedModel() { return model; }
        public void close() throws IOException { try { body.close(); } finally { if (upstream != null) upstream.close(); } }
    }

    static Routed json(int status, Object value, String provider, String account, String model) {
        byte[] bytes = AnthropicToResponses.JSON.writeValueAsBytes(value);
        return new Routed(status, Map.of("content-type", List.of("application/json")), new ByteArrayInputStream(bytes), provider, account, model, null);
    }

    static Routed anthropicError(int status, String type, String message) {
        return json(status, Map.of("type", "error", "error", Map.of("type", type, "message", message)), null, null, null);
    }

    private static String errorType(int status) {
        return switch (status) {
            case 400, 404, 413, 422 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 429 -> "rate_limit_error";
            case 529, 503 -> "overloaded_error";
            default -> "api_error";
        };
    }

    private static String providerMessage(byte[] body) {
        try {
            JsonNode root = AnthropicToResponses.JSON.readTree(body);
            String m = root.path("error").path("message").asString(root.path("detail").asString(""));
            return m.length() > 500 ? m.substring(0, 500) : m;
        } catch (RuntimeException e) { return ""; }
    }

    static boolean transientStatus(int status) { return status == 529 || status == 500 || status == 502 || status == 503 || status == 504; }

    static boolean contextTooLong(byte[] body) {
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return text.contains("prompt is too long") || text.contains("context_length_exceeded") || text.contains("context window")
                || text.contains("maximum context length") || text.contains("too many tokens");
    }

    enum Kind { SUCCESS, QUOTA, TRANSIENT, CONTEXT, FATAL, SKIPPED }
    record Outcome(Kind kind, Routed reply) {}

    public UpstreamTransport.Reply route(UpstreamTransport anthropic, String method, String path, Map<String, String> headers, byte[] body)
            throws IOException, InterruptedException {
        String member = headers.get(MEMBER), session = headers.get(SESSION);
        var outgoing = new HashMap<String, String>();
        headers.forEach((k, v) -> { if (!k.startsWith("x-meter-")) outgoing.put(k, v); });
        // Claude Code sends query strings such as ?beta=true; route on the path alone.
        String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        boolean messages = method.equals("POST") && route.equals("/v1/messages");
        boolean counting = method.equals("POST") && route.equals("/v1/messages/count_tokens");
        if (!messages && !counting) return anthropic.send(method, path, outgoing, body);

        var s = settings.get();
        Instant now = clock.get();
        JsonNode root = null;
        try { root = AnthropicToResponses.JSON.readTree(body); } catch (RuntimeException ignored) { }
        String model = root == null ? "" : root.path("model").asString("");
        boolean clientStreaming = root != null && root.path("stream").asBoolean(false);
        boolean explicit = ModelMapping.openAIModel(model);
        if (explicit && !s.gptModelsEnabled())
            return anthropicError(400, "invalid_request_error", "OpenAI models are turned off by your administrator. Choose a Claude model.");
        if (!explicit && !s.fallbackEnabled()) return anthropic.send(method, path, outgoing, body);

        String upstreamModel = s.mapping().upstream(model);
        String assigned = explicit ? null : state.assignment(session, now);
        String strategy = RoutingPolicy.STRATEGIES.contains(s.strategy()) ? s.strategy() : "claude_first";
        var order = RoutingPolicy.order(env.deployments(), new RoutingPolicy.Request(explicit, body.length / 4, assigned), strategy,
                d -> new RoutingPolicy.Signals(health.view(d.id()), env.usedPercent(d, now), env.servesModel(d, upstreamModel)), now)
                .stream().filter(d -> !d.broker() || member != null).toList();

        if (counting) {
            if (order.isEmpty() || !order.getFirst().anthropic()) return estimate(body);
            var reply = transport(order.getFirst(), anthropic).send(method, path, outgoing, body);
            if (reply.status() == 429 || transientStatus(reply.status())) { reply.close(); return estimate(body); }
            return reply;
        }

        Routed claudeRefusal = null, lastFailure = null;
        for (var d : order) {
            Outcome o = d.anthropic()
                    ? attemptAnthropic(d, transport(d, anthropic), method, path, outgoing,
                        assigned != null && !assigned.equals(d.id()) ? withoutThinking(body) : body, clientStreaming, s)
                    : attemptOpenAI(d, s, body, member, session);
            switch (o.kind()) {
                case SUCCESS -> { if (!explicit) state.assign(session, d.id(), clock.get()); return o.reply(); }
                case QUOTA -> { if (d.anthropic()) claudeRefusal = o.reply(); else lastFailure = o.reply(); }
                case TRANSIENT, CONTEXT -> { if (o.reply() != null) lastFailure = o.reply(); }
                case FATAL -> { return o.reply(); }
                case SKIPPED -> { }
            }
        }
        if (claudeRefusal != null) return claudeRefusal; // everything else is exhausted: Anthropic's own refusal
        if (lastFailure != null) return lastFailure;
        return anthropicError(429, "rate_limit_error", explicit ? "No OpenAI capacity is available right now. Try again later or choose a Claude model."
                : "Claude, Codex and your OpenAI fallback budget are all unavailable right now. Try again later.");
    }

    private UpstreamTransport transport(Deployment d, UpstreamTransport primary) {
        var extra = env.anthropic(d);
        return extra == null ? primary : extra;
    }

    /** Waits for response headers at most {@code seconds}; a slow first byte counts as a transient failure. */
    private static <T extends AutoCloseable> T withTimeout(Callable<T> call, int seconds) throws Exception {
        if (seconds <= 0) return call.call();
        Future<T> future = ATTEMPTS.submit(call);
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException e) {
            if (!future.cancel(true)) { try { future.get().close(); } catch (Exception ignored) { } }
            throw new IOException("No response headers within " + seconds + "s");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    static long backoff(int attempt, Long retryAfterMs) {
        long base = 500L * (long) Math.pow(3, attempt); // 0.5 s, 1.5 s, 4.5 s
        return retryAfterMs != null && retryAfterMs <= 5_000 ? Math.max(base, retryAfterMs) : base;
    }

    /** Records a transient failure; true when the deployment should not be retried now. */
    private boolean giveUp(Deployment d, String reason, RoutingSettings.Values s, int attempt) {
        boolean cooled = health.transientFailure(d.id(), reason, Math.max(1, s.allowedFails()), Math.max(5, s.cooldownSeconds()), clock.get());
        if (cooled) LOG.warn("Deployment {} cooling down for {}s after repeated failures ({})", d.id(), s.cooldownSeconds(), reason);
        return cooled || attempt >= s.transientRetries();
    }

    Outcome attemptAnthropic(Deployment d, UpstreamTransport transport, String method, String path, Map<String, String> headers,
                             byte[] body, boolean streaming, RoutingSettings.Values s) throws InterruptedException {
        Outcome last = new Outcome(Kind.SKIPPED, null);
        for (int attempt = 0; ; attempt++) {
            var permit = health.acquire(d.id());
            long started = System.nanoTime();
            UpstreamTransport.Reply reply;
            try { reply = withTimeout(() -> transport.send(method, path, headers, body), streaming ? s.firstByteTimeoutSeconds() : 0); }
            catch (InterruptedException e) { close(permit); throw e; }
            catch (Exception e) {
                close(permit);
                if (giveUp(d, e.getClass().getSimpleName(), s, attempt)) return last;
                env.sleep(backoff(attempt, null));
                continue;
            }
            int status = reply.status();
            if (status < 400) {
                health.success(d.id(), (System.nanoTime() - started) / 1_000_000);
                Closeable both = () -> { try { reply.close(); } finally { close(permit); } };
                return new Outcome(Kind.SUCCESS, new Routed(status, reply.headers(), reply.body(), "anthropic", d.account(), null, both));
            }
            byte[] bytes;
            Map<String, List<String>> replyHeaders = reply.headers();
            try (reply) { bytes = reply.body().readNBytes(64 * 1024); } catch (Exception e) { bytes = new byte[0]; }
            close(permit);
            var buffered = new Routed(status, replyHeaders, new ByteArrayInputStream(bytes), null, null, null, null);
            Long retryAfter = number(first(replyHeaders, "retry-after"));
            if (status == 429) {
                Long reset = number(first(replyHeaders, "anthropic-ratelimit-unified-reset"));
                Instant now = clock.get();
                health.quotaExhausted(d.id(), reset != null ? Instant.ofEpochSecond(reset) : now.plusSeconds(retryAfter != null ? retryAfter : 30),
                        "quota: " + Objects.requireNonNullElse(first(replyHeaders, "anthropic-ratelimit-unified-representative-claim"), "rate limit"));
                return new Outcome(Kind.QUOTA, buffered);
            }
            if (transientStatus(status)) {
                last = new Outcome(Kind.TRANSIENT, buffered);
                if (giveUp(d, "HTTP " + status, s, attempt)) return last;
                env.sleep(backoff(attempt, retryAfter == null ? null : retryAfter * 1000));
                continue;
            }
            if (status == 400 && contextTooLong(bytes)) return new Outcome(Kind.CONTEXT, buffered);
            health.otherFailure(d.id(), "HTTP " + status);
            return new Outcome(Kind.FATAL, buffered);
        }
    }

    Outcome attemptOpenAI(Deployment d, RoutingSettings.Values s, byte[] body, String member, String session) throws InterruptedException {
        var tier = env.openai(d);
        AnthropicToResponses.Translation t;
        try { t = AnthropicToResponses.translate(body, s.mapping(), session, d.broker()); }
        catch (AnthropicToResponses.Unsupported e) { return new Outcome(Kind.FATAL, anthropicError(400, "invalid_request_error", e.getMessage())); }
        byte[] request = AnthropicToResponses.JSON.writeValueAsBytes(t.body());
        String label = d.codex() ? "Codex" : "OpenAI API";
        Outcome last = new Outcome(Kind.SKIPPED, null);
        for (int attempt = 0; ; attempt++) {
            var permit = health.acquire(d.id());
            long started = System.nanoTime();
            HttpResponse<InputStream> response;
            try { response = withTimeout(() -> new ClosableResponse(tier.send(request, member, session)), s.firstByteTimeoutSeconds()).response(); }
            catch (InterruptedException e) { close(permit); throw e; }
            catch (Exception e) {
                close(permit);
                LOG.warn("Deployment {} unreachable ({})", d.id(), e.getClass().getSimpleName());
                if (giveUp(d, e.getClass().getSimpleName(), s, attempt)) return last;
                env.sleep(backoff(attempt, null));
                continue;
            }
            tier.observe(response);
            int status = response.statusCode();
            if (status == 200) {
                health.success(d.id(), (System.nanoTime() - started) / 1_000_000);
                var converter = new ResponsesToAnthropic(t.requestedModel(), t.toolNames());
                var hs = new LinkedHashMap<String, List<String>>();
                hs.put("request-id", List.of(response.headers().firstValue("x-request-id").orElse(d.account() + "-" + UUID.randomUUID())));
                Closeable both = () -> { try { response.body().close(); } finally { close(permit); } };
                InputStream out;
                if (t.clientStreaming()) {
                    hs.put("content-type", List.of("text/event-stream"));
                    out = ResponsesToAnthropic.streaming(response.body(), converter);
                } else {
                    hs.put("content-type", List.of("application/json"));
                    try { out = new ByteArrayInputStream(ResponsesToAnthropic.aggregate(response.body(), converter)); }
                    catch (IOException e) {
                        close(both);
                        if (giveUp(d, "reply interrupted", s, attempt)) return last;
                        continue;
                    }
                }
                return new Outcome(Kind.SUCCESS, new Routed(200, hs, out, "openai", d.account(), t.upstreamModel(), both));
            }
            byte[] error;
            try (InputStream in = response.body()) { error = in.readNBytes(64 * 1024); } catch (IOException e) { error = new byte[0]; }
            close(permit);
            String message = providerMessage(error);
            var refusal = anthropicError(status == 429 ? 429 : status >= 500 ? 529 : status, errorType(status),
                    label + " refused the request" + (message.isEmpty() ? "." : ": " + message));
            if (status == 429) {
                Long reset = number(response.headers().firstValue("x-codex-primary-reset-at").orElse(null));
                Instant now = clock.get();
                health.quotaExhausted(d.id(), reset != null ? Instant.ofEpochSecond(reset) : now.plusSeconds(d.broker() ? 60 : 900),
                        d.broker() ? "member budget or weekly cap" : "quota");
                return new Outcome(Kind.QUOTA, refusal);
            }
            if (transientStatus(status)) {
                last = new Outcome(Kind.TRANSIENT, refusal);
                if (giveUp(d, "HTTP " + status, s, attempt)) return last;
                env.sleep(backoff(attempt, null));
                continue;
            }
            if (status == 400 && contextTooLong(error)) return new Outcome(Kind.CONTEXT, refusal);
            health.otherFailure(d.id(), "HTTP " + status);
            LOG.warn("Deployment {} refused with HTTP {}", d.id(), status);
            // A Codex refusal may be specific to that login, so the next deployment is tried; the broker's refusal is final.
            return new Outcome(d.broker() ? Kind.FATAL : Kind.CONTEXT, refusal);
        }
    }

    private record ClosableResponse(HttpResponse<InputStream> response) implements AutoCloseable {
        public void close() throws IOException { response.body().close(); }
    }

    private static void close(AutoCloseable c) { try { c.close(); } catch (Exception ignored) { } }

    private static String first(Map<String, List<String>> headers, String name) {
        for (var e : headers.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().getFirst();
        return null;
    }

    private static Long number(String value) {
        if (value == null) return null;
        try { return (long) Double.parseDouble(value.strip()); } catch (NumberFormatException e) { return null; }
    }

    private static Routed estimate(byte[] body) {
        // Rough local estimate (~4 bytes per token) when the Anthropic counter is unavailable.
        return json(200, Map.of("input_tokens", Math.max(1, body.length / 4)), null, null, null);
    }

    static byte[] withoutThinking(byte[] body) {
        try {
            var root = (tools.jackson.databind.node.ObjectNode) AnthropicToResponses.JSON.readTree(body);
            root.remove("thinking");
            root.remove("context_management"); // its thinking-clearing edits require thinking to be enabled
            return AnthropicToResponses.JSON.writeValueAsBytes(root);
        } catch (RuntimeException e) { return body; }
    }
}
