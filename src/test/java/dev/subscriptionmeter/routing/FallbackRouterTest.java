package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.upstream.UpstreamTransport;
import java.io.*;
import java.math.BigDecimal;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class FallbackRouterTest {
    static final String SSE_OK = "data: {\"type\":\"response.created\"}\n\n"
            + "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"message\"}}\n\n"
            + "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"hi\"}\n\n"
            + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}}\n\n";
    static final Instant START = Instant.parse("2026-11-25T12:00:00Z");
    static final long RESET = START.plusSeconds(3600).getEpochSecond();
    final AtomicReference<Instant> now = new AtomicReference<>(START);

    record Values(boolean gpt, boolean fallback, String strategy, int allowedFails, int cooldown, int retries, int timeout) {
        Values(boolean gpt, boolean fallback) { this(gpt, fallback, "claude_first", 3, 60, 2, 90); }
        Values strategy(String s) { return new Values(gpt, fallback, s, allowedFails, cooldown, retries, timeout); }
    }

    static Deployment dep(String id, String kind, String account, int priority) {
        return new Deployment(id, id, kind, account, priority, 100, true, null, null, null);
    }

    Values values;
    RoutingState state;
    DeploymentHealth health;
    FakeTier codex, broker;
    FakeAnthropic anthropic, claude2;
    List<Deployment> deployments;
    final Map<String, Double> used = new HashMap<>();
    final Set<String> notServing = new HashSet<>();
    final List<Long> sleeps = new ArrayList<>();
    FallbackRouter router;

    static final class FakeTier implements ResponsesTarget {
        final String account; final List<String> bodies = new ArrayList<>(); final Deque<Integer> statuses = new ArrayDeque<>();
        int status = 200; String body = SSE_OK; long delayMs;
        FakeTier(String account) { this.account = account; }
        public String account() { return account; }
        public boolean available() { return true; }
        public HttpResponse<InputStream> send(byte[] b, String member, String session) throws IOException {
            bodies.add(new String(b, StandardCharsets.UTF_8));
            if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException e) { throw new IOException(e); }
            int code = statuses.isEmpty() ? status : statuses.removeFirst();
            return response(code, code == 200 ? SSE_OK : body);
        }
    }

    static final class FakeAnthropic implements UpstreamTransport {
        final List<Map<String, String>> calls = new ArrayList<>(); final List<String> bodies = new ArrayList<>(); final Deque<Integer> statuses = new ArrayDeque<>();
        int status = 200; String errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}";
        Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
        public Reply send(String method, String path, Map<String, String> h, byte[] body) {
            calls.add(h); bodies.add(new String(body, StandardCharsets.UTF_8));
            int code = statuses.isEmpty() ? status : statuses.removeFirst();
            byte[] bytes = (code == 200 ? "{\"type\":\"message\"}" : errorBody).getBytes();
            var hs = headers;
            return new Reply() {
                public int status() { return code; }
                public Map<String, List<String>> headers() { return hs; }
                public InputStream body() { return new ByteArrayInputStream(bytes); }
                public void close() { }
            };
        }
    }

    static HttpResponse<InputStream> response(int status, String body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public InputStream body() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public java.net.URI uri() { return java.net.URI.create("http://x"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    @BeforeEach void setUp() {
        values = new Values(true, true);
        state = new RoutingState();
        health = new DeploymentHealth(null);
        codex = new FakeTier("codex"); broker = new FakeTier("broker");
        anthropic = new FakeAnthropic(); claude2 = new FakeAnthropic();
        deployments = new ArrayList<>(List.of(dep("claude-primary", "anthropic", "primary", 1), dep("codex-primary", "codex", "codex", 1),
                dep("openai-api-key", "broker", "broker", 2)));
        RoutingSettings settings = new RoutingSettings(null) {
            @Override public RoutingSettings.Values get() {
                return new RoutingSettings.Values(values.gpt(), values.fallback(), true, true, "gpt-6.2-sol", "gpt-6.1-terra", "gpt-6-luna",
                        BigDecimal.TEN, values.strategy(), values.allowedFails(), values.cooldown(), values.retries(), values.timeout(), Instant.EPOCH, "test");
            }
        };
        var env = new FallbackRouter.Environment() {
            public List<Deployment> deployments() { return deployments; }
            public Double usedPercent(Deployment d, Instant at) { return used.get(d.id()); }
            public boolean servesModel(Deployment d, String model) { return !notServing.contains(d.id() + ":" + model); }
            public UpstreamTransport anthropic(Deployment d) { return d.id().equals("claude-primary") ? null : claude2; }
            public ResponsesTarget openai(Deployment d) { return d.broker() ? broker : codex; }
            public void sleep(long millis) { sleeps.add(millis); }
        };
        router = new FallbackRouter(settings, state, health, env, now::get);
    }

    static Map<String, String> headers(String session) {
        var h = new HashMap<String, String>(Map.of("content-type", "application/json", FallbackRouter.MEMBER, "m1"));
        if (session != null) h.put(FallbackRouter.SESSION, session);
        return h;
    }

    static byte[] request(String model, boolean stream) {
        return ("{\"model\":\"" + model + "\",\"max_tokens\":100,\"stream\":" + stream + ",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").getBytes();
    }

    static byte[] thinkingRequest(String model) {
        return ("{\"model\":\"" + model + "\",\"max_tokens\":100,\"stream\":true,\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2000},"
                + "\"context_management\":{\"edits\":[{\"type\":\"clear_thinking_20251015\"}]},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").getBytes();
    }

    UpstreamTransport.Reply send(String session, String model) throws Exception {
        return router.route(anthropic, "POST", "/v1/messages", headers(session), request(model, true));
    }

    static String body(UpstreamTransport.Reply reply) throws IOException { return new String(reply.body().readAllBytes(), StandardCharsets.UTF_8); }

    void claudeOutOfQuota() {
        anthropic.status = 429;
        anthropic.headers = Map.of("anthropic-ratelimit-unified-status", List.of("rejected"), "anthropic-ratelimit-unified-reset", List.of(String.valueOf(RESET)));
    }

    // ---- Existing behaviour ----

    @Test void routingOffIsAPlainPassThroughAndInternalHeadersNeverLeak() throws Exception {
        values = new Values(true, false);
        claudeOutOfQuota();
        var reply = send("s1", "claude-opus-5-5");
        assertThat(reply.status()).isEqualTo(429);
        assertThat(reply.routedProvider()).isNull();
        assertThat(anthropic.calls.getFirst()).doesNotContainKeys(FallbackRouter.MEMBER, FallbackRouter.SESSION).containsKey("content-type");
        assertThat(codex.bodies).isEmpty();
    }

    @Test void claudeServesWhenItHasQuota() throws Exception {
        var reply = send("s1", "claude-opus-5-5");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.routedAccount()).isEqualTo("primary");
        assertThat(codex.bodies).isEmpty();
    }

    @Test void claudeQuotaRefusalMovesToCodexUntilResetAndSessionsStay() throws Exception {
        claudeOutOfQuota();
        var reply = send("s1", "claude-opus-5-5");
        assertThat(reply.routedAccount()).isEqualTo("codex");
        assertThat(reply.servedModel()).isEqualTo("gpt-6.2-sol");
        assertThat(body(reply)).contains("event: message_start", "\"model\":\"claude-opus-5-5\"", "message_stop");
        assertThat(codex.bodies.getFirst()).doesNotContain("max_output_tokens");
        anthropic.calls.clear();
        assertThat(send("s2", "claude-sonnet-5-5").routedAccount()).isEqualTo("codex"); // Claude known to be out: not even tried
        assertThat(anthropic.calls).isEmpty();
        now.set(Instant.ofEpochSecond(RESET + 1));
        anthropic.status = 200;
        assertThat(send("s1", "claude-opus-5-5").routedAccount()).isEqualTo("codex"); // a moved session stays
        assertThat(send("s3", "claude-opus-5-5").routedAccount()).isEqualTo("primary"); // a new one returns to Claude
        now.set(Instant.ofEpochSecond(RESET + 1).plus(RoutingState.STICKY).plusSeconds(1));
        assertThat(send("s1", "claude-opus-5-5").routedAccount()).isEqualTo("primary"); // idle beyond the window
    }

    @Test void codexOutOfQuotaMovesToTheMembersApiKeyAndIsThenSkipped() throws Exception {
        claudeOutOfQuota();
        codex.status = 429; codex.body = "{\"error\":{\"message\":\"usage limit reached\"}}";
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("claude-haiku-5-5", false));
        assertThat(reply.routedAccount()).isEqualTo("broker");
        assertThat(body(reply)).contains("\"type\":\"message\"", "\"text\":\"hi\"");
        assertThat(broker.bodies.getFirst()).contains("\"max_output_tokens\":100", "gpt-6-luna");
        codex.bodies.clear();
        send("s2", "claude-haiku-5-5");
        assertThat(codex.bodies).isEmpty();
    }

    @Test void whenEverythingIsExhaustedClaudesOwnRefusalIsReturned() throws Exception {
        claudeOutOfQuota();
        codex.status = 429; broker.status = 429; broker.body = "{\"error\":{\"message\":\"budget used up\"}}";
        var reply = send("s1", "claude-opus-5-5");
        assertThat(reply.status()).isEqualTo(429);
        assertThat(body(reply)).contains("rate_limit_error");
    }

    @Test void brokerRejectionOtherThanQuotaIsFinal() throws Exception {
        claudeOutOfQuota();
        codex.status = 429;
        broker.status = 400; broker.body = "{\"error\":{\"message\":\"bad tool schema\"}}";
        var reply = send("s1", "claude-opus-5-5");
        assertThat(reply.status()).isEqualTo(400);
        assertThat(body(reply)).contains("invalid_request_error", "bad tool schema");
    }

    @Test void explicitOpenAIModelsSkipClaudeAndCanBeTurnedOff() throws Exception {
        values = new Values(true, false);
        assertThat(send("s1", "gpt-6.2-astra").routedAccount()).isEqualTo("codex");
        assertThat(anthropic.calls).isEmpty();
        assertThat(codex.bodies.getFirst()).contains("\"model\":\"gpt-6.2-astra\"");
        values = new Values(false, true);
        var off = send("s1", "gpt-6.2-astra");
        assertThat(off.status()).isEqualTo(400);
        assertThat(body(off)).contains("turned off");
    }

    @Test void queryStringsAndCountTokensAreHandled() throws Exception {
        assertThat(router.route(anthropic, "POST", "/v1/messages?beta=true", headers("s1"), request("gpt-6.1-terra", true)).routedAccount()).isEqualTo("codex");
        assertThat(body(router.route(anthropic, "POST", "/v1/messages/count_tokens?beta=true", headers("s1"), request("gpt-6.1-terra", false)))).contains("input_tokens");
        assertThat(anthropic.calls).isEmpty();
        claudeOutOfQuota();
        assertThat(body(router.route(anthropic, "POST", "/v1/messages/count_tokens", headers("s2"), request("claude-opus-5-5", false)))).contains("input_tokens");
        anthropic.status = 200;
        assertThat(router.route(anthropic, "GET", "/v1/models", headers(null), new byte[0]).status()).isEqualTo(200);
    }

    // ---- Balanced strategy ----

    @Test void balancedSendsNewSessionsToMoreHeadroomKeepsThemAndTiesStayOnClaude() throws Exception {
        values = values.strategy("balanced");
        used.put("claude-primary", 70.0); used.put("codex-primary", 20.0);
        assertThat(send("s1", "claude-opus-5-5").routedAccount()).isEqualTo("codex");
        used.put("claude-primary", 30.0); used.put("codex-primary", 60.0);
        assertThat(send("s2", "claude-opus-5-5").routedAccount()).isEqualTo("primary");
        assertThat(send("s1", "claude-opus-5-5").routedAccount()).isEqualTo("codex");
        used.put("claude-primary", 50.0); used.put("codex-primary", 50.0);
        assertThat(send("s3", "claude-opus-5-5").routedAccount()).isEqualTo("primary");
        assertThat(state.sessionsByDeployment(now.get())).containsEntry("codex-primary", 1L).containsEntry("claude-primary", 2L);
    }

    @Test void aCodexSessionMovesToClaudeWithoutThinkingBeforeTheApiKey() throws Exception {
        values = values.strategy("balanced");
        used.put("claude-primary", 80.0); used.put("codex-primary", 10.0);
        router.route(anthropic, "POST", "/v1/messages", headers("s1"), thinkingRequest("claude-opus-5-5"));
        codex.status = 429;
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s1"), thinkingRequest("claude-opus-5-5"));
        assertThat(reply.routedAccount()).isEqualTo("primary");
        assertThat(anthropic.bodies.getFirst()).doesNotContain("thinking", "context_management");
        assertThat(broker.bodies).isEmpty();
        router.route(anthropic, "POST", "/v1/messages", headers("s9"), thinkingRequest("claude-opus-5-5"));
        assertThat(anthropic.bodies.getLast()).contains("thinking");
    }

    // ---- Reliability ----

    @Test void transientErrorsAreRetriedWithBackoffOnTheSameDeployment() throws Exception {
        anthropic.statuses.addAll(List.of(529, 503));
        var reply = send("s1", "claude-opus-5-5");
        assertThat(reply.routedAccount()).isEqualTo("primary");
        assertThat(anthropic.calls).hasSize(3);
        assertThat(sleeps).containsExactly(500L, 1500L);
        assertThat(codex.bodies).isEmpty();
        assertThat(health.view("claude-primary").errorRate()).isGreaterThan(0);
    }

    @Test void repeatedFailuresCoolADeploymentDownAndTheNextOneServes() throws Exception {
        anthropic.status = 503; anthropic.errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\"}}";
        var reply = send("s1", "claude-opus-5-5");
        assertThat(reply.routedAccount()).isEqualTo("codex"); // 3 failed attempts, then Codex
        assertThat(anthropic.calls).hasSize(3);
        assertThat(health.view("claude-primary").cooldownUntil()).isAfter(now.get());
        anthropic.calls.clear();
        assertThat(send("s2", "claude-opus-5-5").routedAccount()).isEqualTo("codex"); // in cooldown: skipped
        assertThat(anthropic.calls).isEmpty();
        now.set(now.get().plusSeconds(61));
        anthropic.status = 200;
        assertThat(send("s3", "claude-opus-5-5").routedAccount()).isEqualTo("primary"); // cooldown over
    }

    @Test void aSlowFirstByteFailsOverInsteadOfHanging() throws Exception {
        values = new Values(true, true, "balanced", 1, 60, 0, 1);
        used.put("claude-primary", 90.0); used.put("codex-primary", 5.0);
        codex.delayMs = 2_500;
        long started = System.nanoTime();
        var reply = send("s1", "claude-opus-5-5");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_400);
        assertThat(reply.routedAccount()).isEqualTo("primary");
        assertThat(health.view("codex-primary").lastError()).isEqualTo("IOException");
    }

    @Test void contextTooLongMovesToAnotherDeployment() throws Exception {
        anthropic.status = 400; anthropic.errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 250000 tokens > 200000 maximum\"}}";
        assertThat(send("s1", "claude-opus-5-5").routedAccount()).isEqualTo("codex");
        anthropic.errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad field\"}}";
        var fatal = send("s2", "claude-opus-5-5");
        assertThat(fatal.status()).isEqualTo(400); // other 400s are the client's problem: returned as is
        assertThat(codex.bodies).hasSize(1);
    }

    @Test void aBusyDeploymentIsStillPreferredOverTheApiKey() throws Exception {
        values = values.strategy("balanced");
        deployments.set(1, new Deployment("codex-primary", "Codex", "codex", "codex", 1, 100, true, 1, null, null));
        used.put("claude-primary", 90.0); used.put("codex-primary", 5.0);
        try (var busy = health.acquire("codex-primary")) {
            assertThat(send("s1", "claude-opus-5-5").routedAccount()).isEqualTo("primary"); // Codex at its cap: Claude serves
            claudeOutOfQuota();
            assertThat(send("s2", "claude-opus-5-5").routedAccount()).isEqualTo("codex"); // still ahead of the API key
            assertThat(broker.bodies).isEmpty();
        }
    }

    @Test void theApiKeyIsUsedOnlyWhenEverySubscriptionIsUnavailable() throws Exception {
        values = values.strategy("balanced");
        used.put("claude-primary", 10.0); used.put("codex-primary", 90.0);
        claudeOutOfQuota();
        assertThat(send("s1", "claude-opus-5-5").routedAccount()).isEqualTo("codex");
        codex.status = 429;
        assertThat(send("s2", "claude-opus-5-5").routedAccount()).isEqualTo("broker");
        anthropic.calls.clear(); codex.bodies.clear();
        assertThat(send("s3", "claude-opus-5-5").routedAccount()).isEqualTo("broker");
        assertThat(anthropic.calls).isEmpty();
        assertThat(codex.bodies).isEmpty();
    }

    // ---- Pools and pre-checks ----

    @Test void aSecondClaudeAccountIsBalancedLikeAnyOtherDeployment() throws Exception {
        values = values.strategy("balanced");
        deployments.add(new Deployment("claude-two", "Claude 2", "anthropic", "claude-two", 1, 100, true, null, null, "/run/control-usage-extra/claude-two"));
        used.put("claude-primary", 80.0); used.put("codex-primary", 70.0); used.put("claude-two", 10.0);
        var reply = send("s1", "claude-opus-5-5");
        assertThat(reply.routedProvider()).isEqualTo("anthropic");
        assertThat(reply.routedAccount()).isEqualTo("claude-two");
        assertThat(claude2.calls).hasSize(1);
        assertThat(anthropic.calls).isEmpty();
    }

    @Test void deploymentsThatCannotServeTheModelOrContextAreSkipped() throws Exception {
        claudeOutOfQuota();
        notServing.add("codex-primary:gpt-6.2-sol");
        assertThat(send("s1", "claude-opus-5-5").routedAccount()).isEqualTo("broker");
        assertThat(codex.bodies).isEmpty();
        deployments.set(2, new Deployment("openai-api-key", "Key", "broker", "broker", 2, 100, true, null, 1000, null));
        var tooBig = new String(request("claude-opus-5-5", true)).replace("\"hi\"", "\"" + "x".repeat(8000) + "\"");
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s2"), tooBig.getBytes());
        assertThat(reply.status()).isEqualTo(429); // nothing left that fits: Claude's refusal
    }
}
