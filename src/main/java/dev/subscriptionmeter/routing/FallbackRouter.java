package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.upstream.UpstreamTransport;
import java.io.*;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Routes Claude Code's Anthropic requests: Claude subscription first; when it is out of quota (or the
 * member chose an OpenAI model), the shared Codex login; then the member's own budget-capped broker key.
 * A refusal is only retried elsewhere before any byte reaches the client, so nothing is ever duplicated.
 */
@Component
public class FallbackRouter {
    public static final String MEMBER = "x-meter-member", SESSION = "x-meter-session";
    private static final Logger LOG = LoggerFactory.getLogger(FallbackRouter.class);
    private final RoutingSettings settings;
    private final RoutingState state;
    private final List<ResponsesTarget> tiers;
    private final Supplier<Instant> clock;
    private final java.util.function.Function<Instant, QuotaBalancer.Headroom> headroom;

    @org.springframework.beans.factory.annotation.Autowired
    public FallbackRouter(RoutingSettings settings, RoutingState state, CodexTarget codex, BrokerTarget broker, QuotaBalancer balancer) {
        this(settings, state, List.of(codex, broker), Instant::now, balancer::headroom);
    }

    FallbackRouter(RoutingSettings settings, RoutingState state, List<ResponsesTarget> tiers, Supplier<Instant> clock,
                   java.util.function.Function<Instant, QuotaBalancer.Headroom> headroom) {
        this.settings = settings; this.state = state; this.tiers = tiers; this.clock = clock; this.headroom = headroom;
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
        String model = "";
        try { model = AnthropicToResponses.JSON.readTree(body).path("model").asString(""); } catch (RuntimeException ignored) { }
        boolean explicit = ModelMapping.openAIModel(model);
        if (explicit && !s.gptModelsEnabled())
            return anthropicError(400, "invalid_request_error", "OpenAI models are turned off by your administrator. Choose a Claude model.");
        if (!explicit && !s.fallbackEnabled()) return anthropic.send(method, path, outgoing, body);

        String assigned = explicit ? null : state.assignment(session, now);
        List<String> order = explicit ? openAIOrder(s, member, now) : order(s, assigned, member, now);
        if (counting) {
            if (order.isEmpty() || !order.getFirst().equals("claude")) return estimate(body);
            var reply = anthropic.send(method, path, outgoing, body);
            if (reply.status() == 429) { reply.close(); return estimate(body); }
            return reply;
        }

        Routed claudeRefusal = null, lastFailure = null;
        for (String provider : order) {
            if (provider.equals("claude")) {
                // History written by another provider lacks Claude's signed thinking blocks: send it without thinking.
                byte[] request = assigned != null && !assigned.equals("claude") ? withoutThinking(body) : body;
                var reply = anthropic.send(method, path, outgoing, request);
                if (reply.status() == 429 || reply.status() == 529) { claudeRefusal = refusal(reply, now); continue; }
                if (reply.status() < 400) state.assign(session, "claude", now);
                return reply;
            }
            Routed result = attempt(tier(provider), s, body, member, session, now);
            if (result == null) continue;
            if (result.status() == 200) { if (!explicit) state.assign(session, provider, now); return result; }
            lastFailure = result;
            if (provider.equals("broker") && result.status() != 429) return result; // a broker refusal other than budget is final
        }
        if (claudeRefusal != null) return claudeRefusal; // every alternative is exhausted: Anthropic's own refusal
        if (lastFailure != null) return lastFailure;
        return anthropicError(429, "rate_limit_error", explicit ? "No OpenAI capacity is available right now. Try again later or choose a Claude model."
                : "Claude, Codex and your OpenAI fallback budget are all used up for now. Try again later.");
    }

    /** Claude and Codex in preference order (skipping ones known to be out of quota), then the member's API key. */
    List<String> order(RoutingSettings.Values s, String assigned, String member, Instant now) {
        boolean claudeOk = !state.anthropicBlocked(now);
        boolean codexOk = codexUsable(s, now);
        String first;
        if ("claude".equals(assigned) || "codex".equals(assigned)) first = assigned;
        else first = s.balanced() ? headroom.apply(now).preferred() : "claude";
        var order = new ArrayList<String>();
        for (String p : first.equals("codex") ? List.of("codex", "claude") : List.of("claude", "codex"))
            if (p.equals("claude") ? claudeOk : codexOk) order.add(p);
        if (s.brokerEnabled() && member != null) order.add("broker");
        return order;
    }

    private List<String> openAIOrder(RoutingSettings.Values s, String member, Instant now) {
        var order = new ArrayList<String>();
        if (codexUsable(s, now)) order.add("codex");
        if (s.brokerEnabled() && member != null) order.add("broker");
        return order;
    }

    private boolean codexUsable(RoutingSettings.Values s, Instant now) {
        return s.codexEnabled() && !state.codexBlocked(now) && tier("codex").available();
    }

    private ResponsesTarget tier(String account) {
        for (var t : tiers) if (t.account().equals(account)) return t;
        throw new IllegalStateException("Unknown tier " + account);
    }

    static byte[] withoutThinking(byte[] body) {
        try {
            var root = (tools.jackson.databind.node.ObjectNode) AnthropicToResponses.JSON.readTree(body);
            root.remove("thinking");
            root.remove("context_management"); // its thinking-clearing edits require thinking to be enabled
            return AnthropicToResponses.JSON.writeValueAsBytes(root);
        } catch (RuntimeException e) { return body; }
    }

    /** Consumes Anthropic's refusal, remembering a quota rejection until the reported reset. */
    private Routed refusal(UpstreamTransport.Reply reply, Instant now) throws IOException {
        byte[] bytes;
        Map<String, List<String>> refusalHeaders = reply.headers();
        try (reply) { bytes = reply.body().readNBytes(64 * 1024); }
        if (reply.status() == 429) {
            String status = first(refusalHeaders, "anthropic-ratelimit-unified-status");
            Long reset = number(first(refusalHeaders, "anthropic-ratelimit-unified-reset"));
            Long retry = number(first(refusalHeaders, "retry-after"));
            if ("rejected".equalsIgnoreCase(status) || reset != null)
                state.blockAnthropic(reset != null ? Instant.ofEpochSecond(reset) : now.plusSeconds(retry != null ? retry : 900));
        }
        return new Routed(reply.status(), refusalHeaders, new ByteArrayInputStream(bytes), null, null, null, null);
    }

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

    /** One OpenAI tier: the translated reply, an Anthropic-format refusal, or null when unreachable. */
    Routed attempt(ResponsesTarget tier, RoutingSettings.Values s, byte[] body, String member, String session, Instant now) throws InterruptedException {
        boolean codex = tier.account().equals("codex");
        AnthropicToResponses.Translation t;
        try { t = AnthropicToResponses.translate(body, s.mapping(), session, !codex); }
        catch (AnthropicToResponses.Unsupported e) { return anthropicError(400, "invalid_request_error", e.getMessage()); }
        HttpResponse<InputStream> response;
        try { response = tier.send(AnthropicToResponses.JSON.writeValueAsBytes(t.body()), member, session); }
        catch (IOException e) { LOG.warn("Fallback tier {} unreachable ({})", tier.account(), e.getClass().getSimpleName()); return null; }
        tier.observe(response);
        int status = response.statusCode();
        if (status == 200) {
            var converter = new ResponsesToAnthropic(t.requestedModel(), t.toolNames());
            String requestId = response.headers().firstValue("x-request-id").orElse(tier.account() + "-" + UUID.randomUUID());
            var hs = new LinkedHashMap<String, List<String>>();
            hs.put("request-id", List.of(requestId));
            InputStream out;
            if (t.clientStreaming()) {
                hs.put("content-type", List.of("text/event-stream"));
                out = ResponsesToAnthropic.streaming(response.body(), converter);
            } else {
                hs.put("content-type", List.of("application/json"));
                try { out = new ByteArrayInputStream(ResponsesToAnthropic.aggregate(response.body(), converter)); }
                catch (IOException e) { LOG.warn("Fallback tier {} reply failed ({})", tier.account(), e.getClass().getSimpleName()); return null; }
            }
            return new Routed(200, hs, out, "openai", tier.account(), t.upstreamModel(), response.body());
        }
        byte[] error;
        try (InputStream in = response.body()) { error = in.readNBytes(64 * 1024); } catch (IOException e) { error = new byte[0]; }
        String message = providerMessage(error);
        LOG.warn("Fallback tier {} refused with HTTP {}", tier.account(), status);
        return anthropicError(status == 429 ? 429 : status >= 500 ? 529 : status, errorType(status),
                (codex ? "Codex" : "OpenAI API") + " refused the request" + (message.isEmpty() ? "." : ": " + message));
    }
}
