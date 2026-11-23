package dev.subscriptionmeter.broker;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

class BrokerServerTest {
    static final String REAL = "sk-real-" + "r".repeat(40), ADMIN = "a".repeat(48);
    static final String JSON_REPLY = """
        {"id":"resp_1","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}],
         "usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":400},"output_tokens":200}}""";
    static final String SSE_REPLY = "event: response.created\ndata: {\"type\":\"response.created\"}\n\n"
        + "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n"
        + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":2000,\"output_tokens\":100}}}\n\n";

    JdbcTemplate jdbc;
    BrokerStore store;
    BrokerServer broker;
    HttpServer upstream;
    String base;
    final HttpClient client = HttpClient.newHttpClient();
    final AtomicReference<String> realKey = new AtomicReference<>(REAL);
    final List<String> upstreamAuth = new ArrayList<>(), upstreamBodies = new ArrayList<>();
    volatile int upstreamStatus = 200;
    volatile String upstreamType = "application/json", upstreamReply = JSON_REPLY;
    final Instant now = Instant.parse("2026-11-25T12:00:00Z");

    @BeforeEach void setUp() throws IOException {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:broker" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        store = new BrokerStore(jdbc);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/responses", exchange -> {
            upstreamAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            upstreamBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = upstreamReply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", upstreamType);
            exchange.getResponseHeaders().set("x-request-id", "req_up_1");
            exchange.sendResponseHeaders(upstreamStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
        broker = new BrokerServer(store, () -> Optional.ofNullable(realKey.get()), () -> Optional.of(ADMIN),
                "http://127.0.0.1:" + upstream.getAddress().getPort(), Clock.fixed(now, ZoneOffset.UTC));
        base = "http://127.0.0.1:" + broker.start("127.0.0.1", 0).getPort();
    }

    @AfterEach void tearDown() { broker.stop(0); upstream.stop(0); }

    HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.header("X-Broker-Actor", "owner");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> response) { return BrokerRequests.JSON.readTree(response.body()); }

    String createKey(String extra) throws Exception {
        var created = send("POST", "/admin/keys", ADMIN, "{\"name\":\"Summaries\",\"purpose\":\"session_summary\"" + extra + "}");
        assertThat(created.statusCode()).isEqualTo(201);
        return json(created).path("key").asString();
    }

    List<Map<String, Object>> calls() { return jdbc.queryForList("SELECT * FROM broker_calls ORDER BY created_at,id"); }

    @Test void forwardsWithTheRealKeyAndRecordsActualCost() throws Exception {
        String key = createKey("");
        assertThat(key).startsWith("cuvk_");
        var response = send("POST", "/v1/responses", key, "{\"model\":\"gpt-6-luna\",\"input\":\"hello\",\"max_output_tokens\":999999,\"store\":true}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(JSON_REPLY);
        assertThat(response.headers().firstValue("x-request-id")).contains("req_up_1");
        assertThat(upstreamAuth).containsExactly("Bearer " + REAL);
        var forwarded = BrokerRequests.JSON.readTree(upstreamBodies.getFirst());
        assertThat(forwarded.path("store").asBoolean(true)).isFalse();
        assertThat(forwarded.path("max_output_tokens").asInt()).isEqualTo(16384); // clamped to the key's ceiling
        assertThat(upstreamBodies.getFirst()).doesNotContain("cuvk_");
        var call = calls().getFirst();
        assertThat(call.get("state")).isEqualTo("settled");
        // 600 uncached*0.10 + 400 cached*0.01 + 200 out*0.50 per million
        assertThat((BigDecimal) call.get("cost_usd")).isEqualByComparingTo("0.000164");
        assertThat(call.get("upstream_request_id")).isEqualTo("req_up_1");
    }

    @Test void streamsServerSentEventsUnchangedAndReadsFinalUsage() throws Exception {
        String key = createKey("");
        upstreamType = "text/event-stream"; upstreamReply = SSE_REPLY;
        var response = send("POST", "/v1/responses", key, "{\"model\":\"gpt-6-luna\",\"input\":\"hello\",\"stream\":true}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(SSE_REPLY);
        var call = calls().getFirst();
        assertThat(call.get("state")).isEqualTo("settled");
        assertThat(call.get("streaming")).isEqualTo(true);
        assertThat(((Number) call.get("input_tokens")).longValue()).isEqualTo(2000);
    }

    @Test void refusesUnsafeOrUnknownRequestsAndLogsEachDenial() throws Exception {
        String key = createKey(",\"allowedModels\":[\"gpt-6-luna\"]");
        assertThat(send("POST", "/v1/responses", "cuvk_wrong", "{}").statusCode()).isEqualTo(401);
        assertThat(send("POST", "/v1/responses", REAL, "{}").statusCode()).isEqualTo(401); // the real key is not a virtual key
        assertThat(json(send("POST", "/v1/responses", key, "{\"model\":\"gpt-5-mini\",\"input\":\"x\"}")).path("error").path("type").asString()).isEqualTo("broker_model_not_allowed");
        assertThat(json(send("POST", "/v1/responses", key, "{\"model\":\"gpt-6-luna\",\"input\":\"x\",\"tools\":[{\"type\":\"web_search\"}]}")).path("error").path("type").asString()).isEqualTo("broker_tool_not_allowed");
        assertThat(json(send("POST", "/v1/responses", key, "{\"model\":\"gpt-6-luna\",\"input\":\"x\",\"background\":true}")).path("error").path("type").asString()).isEqualTo("broker_background_not_allowed");
        assertThat(json(send("POST", "/v1/responses", key, "{\"model\":\"gpt-6-luna\",\"previous_response_id\":\"r\"}")).path("error").path("type").asString()).isEqualTo("broker_stored_state_not_allowed");
        assertThat(send("POST", "/v1/responses", key, "not json").statusCode()).isEqualTo(400);
        assertThat(send("GET", "/v1/responses", key, null).statusCode()).isEqualTo(405);
        String any = createKey("");
        assertThat(json(send("POST", "/v1/responses", any, "{\"model\":\"gpt-unpriced\",\"input\":\"x\"}")).path("error").path("type").asString()).isEqualTo("broker_model_unpriced");
        assertThat(upstreamBodies).isEmpty();
        assertThat(calls()).allSatisfy(c -> assertThat(c.get("state")).isEqualTo("denied")).hasSize(8);
    }

    @Test void enforcesKeyBudgetGlobalCapAndRateLimit() throws Exception {
        String limited = createKey(",\"weeklyBudgetUsd\":0.01");
        // Worst case for 16,384 output tokens at $0.50/M is ~$0.0082, so one call fits and a second does not.
        assertThat(send("POST", "/v1/responses", limited, "{\"model\":\"gpt-6-luna\",\"input\":\"x\"}").statusCode()).isEqualTo(200);
        jdbc.update("UPDATE broker_calls SET cost_usd=0.009 WHERE state='settled'");
        assertThat(json(send("POST", "/v1/responses", limited, "{\"model\":\"gpt-6-luna\",\"input\":\"x\"}")).path("error").path("type").asString()).isEqualTo("broker_key_budget_exhausted");

        String other = createKey(",\"rpm\":1");
        assertThat(send("PUT", "/admin/settings", ADMIN, "{\"weeklyCapUsd\":0.01}").statusCode()).isEqualTo(200);
        assertThat(json(send("POST", "/v1/responses", other, "{\"model\":\"gpt-6-luna\",\"input\":\"x\"}")).path("error").path("type").asString()).isEqualTo("broker_weekly_cap_reached");
        send("PUT", "/admin/settings", ADMIN, "{\"weeklyCapUsd\":100}");
        assertThat(send("POST", "/v1/responses", other, "{\"model\":\"gpt-6-luna\",\"input\":\"x\"}").statusCode()).isEqualTo(200);
        assertThat(json(send("POST", "/v1/responses", other, "{\"model\":\"gpt-6-luna\",\"input\":\"x\"}")).path("error").path("type").asString()).isEqualTo("broker_rate_limited");
    }

    @Test void upstreamRejectionIsPassedThroughAndNotCharged() throws Exception {
        String key = createKey("");
        upstreamStatus = 429; upstreamReply = "{\"error\":{\"code\":\"rate_limit_exceeded\"}}";
        var response = send("POST", "/v1/responses", key, "{\"model\":\"gpt-6-luna\",\"input\":\"x\"}");
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("rate_limit_exceeded");
        assertThat(calls().getFirst()).containsEntry("state", "failed");
        assertThat(store.spent(now, null)).isEqualByComparingTo("0");
    }

    @Test void missingRealKeyFailsClosed() throws Exception {
        String key = createKey("");
        realKey.set(null);
        assertThat(json(send("POST", "/v1/responses", key, "{\"model\":\"gpt-6-luna\",\"input\":\"x\"}")).path("error").path("type").asString()).isEqualTo("broker_not_configured");
        assertThat(upstreamBodies).isEmpty();
        assertThat(json(send("GET", "/healthz", null, null)).path("realKeyConfigured").asBoolean()).isFalse();
    }

    @Test void administrationRequiresTokenAndIsLogged() throws Exception {
        assertThat(send("GET", "/admin/state", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/admin/state", "wrong", null).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/admin/keys", ADMIN, "{\"name\":\"x\",\"purpose\":\"BAD PURPOSE\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/admin/keys", ADMIN, "{\"name\":\"x\",\"purpose\":\"other\",\"weeklyBudgetUsd\":-1}").statusCode()).isEqualTo(400);
        String key = createKey(",\"memberId\":\"m-1\",\"rpm\":30");
        var state = json(send("GET", "/admin/state", ADMIN, null));
        assertThat(state.path("weeklyCapUsd").decimalValue()).isEqualByComparingTo("100");
        assertThat(state.path("realKeyConfigured").asBoolean()).isTrue();
        var listed = state.path("keys").get(0);
        assertThat(listed.path("prefix").asString()).isEqualTo(key.substring(0, 12));
        assertThat(state.toString()).doesNotContain(key, REAL);
        String id = listed.path("id").asString();
        assertThat(send("PATCH", "/admin/keys/" + id, ADMIN, "{\"name\":\"Summaries\",\"active\":false}").statusCode()).isEqualTo(200);
        assertThat(send("POST", "/v1/responses", key, "{\"model\":\"gpt-6-luna\",\"input\":\"x\"}").statusCode()).isEqualTo(401);
        assertThat(send("PUT", "/admin/prices", ADMIN, "{\"model\":\"gpt-new\",\"inputUsdPerMTok\":1,\"cachedInputUsdPerMTok\":0.1,\"outputUsdPerMTok\":4}").statusCode()).isEqualTo(200);
        assertThat(store.price("gpt-new")).isPresent();
        assertThat(send("DELETE", "/admin/prices/gpt-new", ADMIN, null).statusCode()).isEqualTo(200);
        var log = json(send("GET", "/admin/log", ADMIN, null));
        assertThat(log.toString()).contains("broker.key.create", "broker.key.revoke", "broker.price.update", "broker.price.delete", "\"actor\":\"owner\"");
        assertThat(log.toString()).doesNotContain(key);
        assertThat(jdbc.queryForObject("SELECT key_hash FROM broker_keys", String.class)).isNotEqualTo(key).hasSize(64);
    }

    @Test void upstreamBaseAcceptsOpenAIStyleUrls() {
        assertThat(BrokerServer.responsesUri("https://api.openai.com")).hasToString("https://api.openai.com/v1/responses");
        assertThat(BrokerServer.responsesUri("https://api.example.com/v1/")).hasToString("https://api.example.com/v1/responses");
        assertThat(BrokerServer.responsesUri("http://127.0.0.1:9/v1")).hasToString("http://127.0.0.1:9/v1/responses");
        for (String bad : List.of("http://api.example.com", "https://user:pw@api.example.com", "https://api.example.com/v1?x=1", "ftp://api.example.com"))
            assertThatThrownBy(() -> BrokerServer.responsesUri(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void stateShowsUpstreamHostButNoSecrets() throws Exception {
        var state = json(send("GET", "/admin/state", ADMIN, null));
        assertThat(state.path("upstreamHost").asString()).isEqualTo("127.0.0.1");
        assertThat(state.toString()).doesNotContain(REAL, ADMIN);
    }

    @Test void longContextRatesApplyAboveThreshold() {
        var price = store.price("gpt-6-luna").orElseThrow();
        assertThat(BrokerStore.cost(price, new BrokerStore.Usage(272_000, 0, 0))).isEqualByComparingTo("0.0272");
        assertThat(BrokerStore.cost(price, new BrokerStore.Usage(300_000, 0, 1000))).isEqualByComparingTo("0.06075");
    }

    @Test void streamScannerIgnoresOversizedAndPartialLines() {
        var scanner = new BrokerRequests.StreamUsage();
        byte[] bytes = SSE_REPLY.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i += 7) scanner.accept(bytes, i, Math.min(7, bytes.length - i)); // split mid-line
        assertThat(scanner.usage()).isEqualTo(new BrokerStore.Usage(2000, 0, 100));
        var empty = new BrokerRequests.StreamUsage();
        byte[] noise = "data: {\"type\":\"response.completed\"".getBytes(StandardCharsets.UTF_8);
        empty.accept(noise, 0, noise.length);
        assertThat(empty.usage()).isNull();
    }
}
