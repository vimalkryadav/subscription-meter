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
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-25T12:00:00Z"));
    RoutingState state;
    Values values;
    FakeTier codex, broker;
    FakeAnthropic anthropic;
    FallbackRouter router;

    record Values(boolean gpt, boolean fallback, boolean codex, boolean broker) {}

    static final class FakeTier implements ResponsesTarget {
        final String account; final List<String> bodies = new ArrayList<>(); int status = 200; String body = SSE_OK; boolean available = true;
        Map<String, List<String>> headers = Map.of();
        RoutingState state;
        FakeTier(String account) { this.account = account; }
        public String account() { return account; }
        public boolean available() { return available; }
        public HttpResponse<InputStream> send(byte[] b, String member, String session) {
            bodies.add(new String(b, StandardCharsets.UTF_8));
            return response(status, headers, body);
        }
        public void observe(HttpResponse<?> r) { if (account.equals("codex") && r.statusCode() == 429) state.blockCodex(Instant.parse("2026-11-26T00:00:00Z")); }
    }

    static final class FakeAnthropic implements UpstreamTransport {
        final List<Map<String, String>> calls = new ArrayList<>(); int status = 200; Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
        public Reply send(String method, String path, Map<String, String> h, byte[] body) {
            calls.add(h);
            byte[] bytes = (status == 200 ? "{\"type\":\"message\"}" : "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}").getBytes();
            return new Reply() {
                public int status() { return status; }
                public Map<String, List<String>> headers() { return headers; }
                public InputStream body() { return new ByteArrayInputStream(bytes); }
                public void close() { }
            };
        }
    }

    static HttpResponse<InputStream> response(int status, Map<String, List<String>> headers, String body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(headers, (a, b) -> true); }
            public InputStream body() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public java.net.URI uri() { return java.net.URI.create("http://x"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    @BeforeEach void setUp() {
        state = new RoutingState();
        values = new Values(true, true, true, true);
        codex = new FakeTier("codex"); codex.state = state;
        broker = new FakeTier("broker"); broker.state = state;
        anthropic = new FakeAnthropic();
        RoutingSettings settings = new RoutingSettings(null) {
            @Override public RoutingSettings.Values get() {
                return new RoutingSettings.Values(values.gpt(), values.fallback(), values.codex(), values.broker(), "gpt-6.2-sol", "gpt-6.1-terra", "gpt-6-luna",
                        BigDecimal.TEN, Instant.EPOCH, "test");
            }
        };
        router = new FallbackRouter(settings, state, List.of(codex, broker), now::get);
    }

    static Map<String, String> headers(String session) {
        var h = new HashMap<String, String>(Map.of("content-type", "application/json", FallbackRouter.MEMBER, "m1"));
        if (session != null) h.put(FallbackRouter.SESSION, session);
        return h;
    }

    static byte[] request(String model, boolean stream) {
        return ("{\"model\":\"" + model + "\",\"max_tokens\":100,\"stream\":" + stream + ",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").getBytes();
    }

    static String body(UpstreamTransport.Reply reply) throws IOException { return new String(reply.body().readAllBytes(), StandardCharsets.UTF_8); }

    @Test void claudeServesWhenItHasQuotaAndInternalHeadersNeverLeak() throws Exception {
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("claude-opus-5-5", true));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.routedProvider()).isNull();
        assertThat(anthropic.calls.getFirst()).doesNotContainKeys(FallbackRouter.MEMBER, FallbackRouter.SESSION).containsKey("content-type");
        assertThat(codex.bodies).isEmpty();
    }

    @Test void fallbackOffPassesClaudesRefusalThrough() throws Exception {
        values = new Values(true, false, true, true);
        anthropic.status = 429;
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("claude-opus-5-5", true));
        assertThat(reply.status()).isEqualTo(429);
        assertThat(codex.bodies).isEmpty();
    }

    @Test void claudeQuotaRefusalMovesToCodexUntilResetAndSessionStays() throws Exception {
        anthropic.status = 429;
        anthropic.headers = Map.of("anthropic-ratelimit-unified-status", List.of("rejected"), "anthropic-ratelimit-unified-reset", List.of("1795611600"));
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("claude-opus-5-5", true));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.routedProvider()).isEqualTo("openai");
        assertThat(reply.routedAccount()).isEqualTo("codex");
        assertThat(reply.servedModel()).isEqualTo("gpt-6.2-sol");
        assertThat(body(reply)).contains("event: message_start", "\"model\":\"claude-opus-5-5\"", "message_stop");
        assertThat(codex.bodies.getFirst()).contains("\"model\":\"gpt-6.2-sol\"").doesNotContain("max_output_tokens");
        // Claude is now known to be out of quota: the next request (another session) skips it entirely.
        anthropic.calls.clear();
        assertThat(router.route(anthropic, "POST", "/v1/messages", headers("s2"), request("claude-sonnet-5-5", true)).routedAccount()).isEqualTo("codex");
        assertThat(anthropic.calls).isEmpty();
        assertThat(codex.bodies.get(1)).contains("gpt-6.1-terra");
        // After Claude resets, a session that moved stays on the fallback; a new session returns to Claude.
        now.set(Instant.ofEpochSecond(1795611601));
        anthropic.status = 200;
        assertThat(router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("claude-opus-5-5", true)).routedAccount()).isEqualTo("codex");
        assertThat(router.route(anthropic, "POST", "/v1/messages", headers("s3"), request("claude-opus-5-5", true)).routedProvider()).isNull();
        // A moved session left idle beyond the sticky window returns to Claude at its next request.
        now.set(Instant.ofEpochSecond(1795611601).plus(RoutingState.STICKY).plusSeconds(1));
        assertThat(router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("claude-opus-5-5", true)).routedProvider()).isNull();
    }

    @Test void codexOutOfQuotaMovesToTheMembersBrokerKey() throws Exception {
        anthropic.status = 429;
        codex.status = 429; codex.body = "{\"error\":{\"message\":\"usage limit reached\"}}";
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("claude-haiku-5-5", false));
        assertThat(reply.routedAccount()).isEqualTo("broker");
        assertThat(reply.headers().get("content-type")).containsExactly("application/json");
        assertThat(body(reply)).contains("\"type\":\"message\"", "\"text\":\"hi\"");
        assertThat(broker.bodies.getFirst()).contains("\"max_output_tokens\":100", "gpt-6-luna");
        // Codex is now marked exhausted; the next fallback goes straight to the broker.
        codex.bodies.clear();
        router.route(anthropic, "POST", "/v1/messages", headers("s2"), request("claude-haiku-5-5", true));
        assertThat(codex.bodies).isEmpty();
    }

    @Test void whenEverythingIsExhaustedClaudesOwnRefusalIsReturned() throws Exception {
        anthropic.status = 429;
        codex.status = 429; broker.status = 429; broker.body = "{\"error\":{\"type\":\"broker_key_budget_exhausted\",\"message\":\"This key's weekly budget is used up.\"}}";
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("claude-opus-5-5", true));
        assertThat(reply.status()).isEqualTo(429);
        assertThat(reply.routedProvider()).isNull();
        assertThat(body(reply)).contains("rate_limit_error");
    }

    @Test void brokerRejectionOtherThanQuotaIsReportedInAnthropicFormat() throws Exception {
        anthropic.status = 429; codex.available = false;
        broker.status = 400; broker.body = "{\"error\":{\"message\":\"bad tool schema\"}}";
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("claude-opus-5-5", true));
        assertThat(reply.status()).isEqualTo(400);
        assertThat(body(reply)).contains("invalid_request_error", "bad tool schema");
    }

    @Test void explicitOpenAIModelsSkipClaudeAndCanBeTurnedOff() throws Exception {
        values = new Values(true, false, true, true); // explicit choice works even with automatic fallback off
        var reply = router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("gpt-6.2-astra", true));
        assertThat(reply.routedAccount()).isEqualTo("codex");
        assertThat(anthropic.calls).isEmpty();
        assertThat(codex.bodies.getFirst()).contains("\"model\":\"gpt-6.2-astra\"");
        values = new Values(false, true, true, true);
        var off = router.route(anthropic, "POST", "/v1/messages", headers("s1"), request("gpt-6.2-astra", true));
        assertThat(off.status()).isEqualTo(400);
        assertThat(body(off)).contains("turned off");
    }

    @Test void claudeCodeQueryStringsAreRoutedLikeThePlainPath() throws Exception {
        var reply = router.route(anthropic, "POST", "/v1/messages?beta=true", headers("s1"), request("gpt-6.1-terra", true));
        assertThat(reply.routedAccount()).isEqualTo("codex");
        assertThat(anthropic.calls).isEmpty();
        assertThat(body(router.route(anthropic, "POST", "/v1/messages/count_tokens?beta=true", headers("s1"), request("gpt-6.1-terra", false)))).contains("input_tokens");
        assertThat(anthropic.calls).isEmpty();
    }

    @Test void countTokensIsEstimatedLocallyForFallbackRequests() throws Exception {
        var reply = router.route(anthropic, "POST", "/v1/messages/count_tokens", headers("s1"), request("gpt-6-luna", false));
        assertThat(body(reply)).matches("\\{\"input_tokens\":\\d+\\}");
        assertThat(anthropic.calls).isEmpty();
        anthropic.status = 429;
        assertThat(body(router.route(anthropic, "POST", "/v1/messages/count_tokens", headers("s2"), request("claude-opus-5-5", false)))).contains("input_tokens");
        anthropic.status = 200;
        assertThat(router.route(anthropic, "GET", "/v1/models", headers(null), new byte[0]).status()).isEqualTo(200);
    }
}
