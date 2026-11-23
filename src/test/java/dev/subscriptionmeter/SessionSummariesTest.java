package dev.subscriptionmeter;

import dev.subscriptionmeter.deployment.DeploymentState;
import dev.subscriptionmeter.summaries.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class SessionSummariesTest {
    static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z"); // a Wednesday
    JdbcTemplate jdbc;
    OpenAIApiBudget budget;
    Path keyFile;
    final List<ResponsesClient.Request> calls = new ArrayList<>();
    Object next;

    @BeforeEach void setUp() throws IOException {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:summaries" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("accounting-schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        budget = new OpenAIApiBudget(jdbc);
        keyFile = Files.createTempFile("openai", ".key");
        Files.writeString(keyFile, "sk-test-" + "a".repeat(40) + "\n");
        jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES('m1','Alice','h','sm_x',60,4)");
        next = ok("Fixed a failing payments test in billing-service.", "debugging", 1000, 80);
    }

    static ResponsesClient.Result ok(String summary, String category, long in, long out) {
        return new ResponsesClient.Result("{\"summary\":\"" + summary + "\",\"category\":\"" + category + "\"}", new OpenAIApiBudget.Usage(in, 0, out));
    }

    SessionSummarizer summarizer() {
        ResponsesClient client = request -> {
            calls.add(request);
            if (next instanceof ResponsesClient.Rejected r) throw r;
            if (next instanceof IOException e) throw e;
            return (ResponsesClient.Result) next;
        };
        return new SessionSummarizer(jdbc, budget, new OpenAIApiKey(keyFile.toString()), client, new DeploymentState(), true, 30);
    }

    void request(String id, String session, String agent, String model, Instant at, String body) {
        jdbc.update("INSERT INTO requests(id,member_id,path,model,started_at,client_session_id,client_agent_id) VALUES(?,?,?,?,?,?,?)",
                id, "m1", "/v1/messages", model, Timestamp.from(at), session, agent);
        jdbc.update("INSERT INTO request_details(request_id,started_at,member_id,upstream_mode,method,path,request_body) VALUES(?,?,?,?,?,?,?)",
                id, Timestamp.from(at), "m1", "subscription", "POST", "/v1/messages", body);
    }

    static String conversation(String userText) {
        return """
            {"model":"claude-opus","system":[{"type":"text","text":"You are Claude Code.\\nPrimary working directory: /home/alice/billing-service"}],
             "tools":[{"name":"Edit","input_schema":{}}],
             "messages":[{"role":"user","content":[{"type":"text","text":"<system-reminder>secret context</system-reminder>%s"}]},
               {"role":"assistant","content":[{"type":"text","text":"Looking at the test."},{"type":"tool_use","id":"t1","name":"Edit","input":{"file_path":"src/PaymentTest.java","old_string":"CODE"}}]},
               {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"TOOL OUTPUT"}]}]}
            """.formatted(userText);
    }

    Map<String, Object> summary(String session) {
        return jdbc.queryForMap("SELECT * FROM session_summaries WHERE session_id=?", session);
    }

    @Test void digestKeepsPromptsAndToolTargetsButNotOutputsOrSystemText() {
        String digest = SessionDigest.build(conversation("Fix the failing payment test"), 24_000).orElseThrow();
        assertThat(digest).contains("PROJECT: billing-service", "USER: Fix the failing payment test", "ASSISTANT: Looking at the test.",
                "TOOL: Edit src/PaymentTest.java");
        assertThat(digest).doesNotContain("secret context", "TOOL OUTPUT", "CODE", "You are Claude Code");
        assertThat(SessionDigest.build("{\"messages\":[{\"role\":\"assistant\",\"content\":\"hi\"}]}", 1000)).isEmpty();
        assertThat(SessionDigest.build("not json", 1000)).isEmpty();
        var many = new StringBuilder("{\"messages\":[");
        for (int i = 0; i < 500; i++) many.append(i == 0 ? "" : ",").append("{\"role\":\"user\",\"content\":\"step ").append(i).append(" ").append("x".repeat(80)).append("\"}");
        String bounded = SessionDigest.build(many.append("]}").toString(), 4000).orElseThrow();
        assertThat(bounded.length()).isLessThanOrEqualTo(4000);
        assertThat(bounded).contains("step 0 ", "step 499 ", "entries omitted");
    }

    @Test void weeklyCapReservesUnderLockAndResetsOnMonday() {
        assertThat(OpenAIApiBudget.weekStart(NOW)).isEqualTo(Instant.parse("2026-11-23T00:00:00Z"));
        assertThat(OpenAIApiBudget.weekStart(Instant.parse("2026-11-23T00:00:00Z"))).isEqualTo(Instant.parse("2026-11-23T00:00:00Z"));
        var settings = budget.settings();
        assertThat(settings.weeklyCapUsd()).isEqualByComparingTo("100");
        assertThat(OpenAIApiBudget.cost(settings, new OpenAIApiBudget.Usage(1_000_000, 200_000, 100_000)))
                .isEqualByComparingTo("0.132"); // 800k*0.10 + 200k*0.01 + 100k*0.50, per million
        budget.update(new BigDecimal("1.00"), true, "gpt-6-luna", new BigDecimal("0.10"), new BigDecimal("0.01"), new BigDecimal("0.50"), "test");
        var first = budget.reserve("session_summary", "a", "gpt-6-luna", new BigDecimal("0.60"), NOW).orElseThrow();
        assertThat(budget.reserve("session_summary", "b", "gpt-6-luna", new BigDecimal("0.60"), NOW)).isEmpty();
        budget.settle(first, new OpenAIApiBudget.Usage(1000, 0, 100), budget.settings());
        assertThat(budget.spent(NOW)).isEqualByComparingTo("0.00015");
        assertThat(budget.reserve("session_summary", "b", "gpt-6-luna", new BigDecimal("0.60"), NOW)).isPresent();
        // Last week's spending does not count against this week.
        assertThat(budget.spent(NOW.plus(Duration.ofDays(7)))).isEqualByComparingTo("0");
        var unknown = budget.reserve("session_summary", "c", "gpt-6-luna", new BigDecimal("0.30"), NOW).orElseThrow();
        budget.unknown(unknown);
        assertThat(budget.spent(NOW)).isEqualByComparingTo("0.90015");
    }

    @Test void summarizesIdleSessionsOnceAndWaitsForActiveOnes() {
        var start = NOW.minus(Duration.ofHours(2));
        request("r1", "s-idle", null, "claude-opus", start, conversation("First prompt"));
        request("r2", "s-idle", null, "claude-opus", start.plusSeconds(60), conversation("Fix the failing payment test"));
        request("r3", "s-idle", null, "claude-haiku", start.plusSeconds(90), "{\"messages\":[{\"role\":\"user\",\"content\":\"Generate a title\"}]}");
        request("r4", "s-idle", "agent-1", "claude-opus", start.plusSeconds(100), conversation("Sub-agent task"));
        request("r5", "s-active", null, "claude-opus", NOW.minus(Duration.ofMinutes(5)), conversation("Still working"));

        assertThat(summarizer().runOnce(NOW)).isEqualTo(1);
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().input()).contains("Fix the failing payment test", "4 requests (1 from sub-agents)")
                .doesNotContain("Generate a title", "Sub-agent task");
        assertThat(calls.getFirst().apiKey()).startsWith("sk-test-");
        var row = summary("s-idle");
        assertThat(row.get("status")).isEqualTo("ok");
        assertThat(row.get("category")).isEqualTo("debugging");
        assertThat(row.get("summary")).isEqualTo("Fixed a failing payments test in billing-service.");
        assertThat(summary("s-active").get("status")).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT state FROM openai_api_spend", String.class)).isEqualTo("settled");

        // Nothing new: no second call. New activity later: summarized again.
        assertThat(summarizer().runOnce(NOW.plusSeconds(60))).isZero();
        request("r6", "s-idle", null, "claude-opus", NOW.plusSeconds(120), conversation("Now add a regression test"));
        next = ok("Fixed and covered the payments bug.", "testing", 1000, 80);
        summarizer().runOnce(NOW.plus(Duration.ofHours(1)));
        assertThat(summary("s-idle").get("category")).isEqualTo("testing");
    }

    @Test void noKeyDisabledOrExhaustedBudgetMakesNoCalls() throws IOException {
        request("r1", "s1", null, "claude-opus", NOW.minus(Duration.ofHours(2)), conversation("Hello"));
        Files.writeString(keyFile, "");
        assertThat(summarizer().runOnce(NOW)).isZero();
        Files.writeString(keyFile, "sk-test-" + "a".repeat(40));
        budget.update(new BigDecimal("100"), false, "gpt-6-luna", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "test");
        assertThat(summarizer().runOnce(NOW)).isZero();
        budget.update(BigDecimal.ZERO, true, "gpt-6-luna", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "test");
        assertThat(summarizer().runOnce(NOW)).isEqualTo(1);
        assertThat(calls).isEmpty();
        assertThat(summary("s1").get("status")).isEqualTo("budget_exhausted");
    }

    @Test void failuresKeepEarlierSummaryReleaseRejectedSpendAndBackOff() {
        request("r1", "s1", null, "claude-opus", NOW.minus(Duration.ofHours(3)), conversation("Hello"));
        summarizer().runOnce(NOW.minus(Duration.ofHours(2)));
        request("r2", "s1", null, "claude-opus", NOW.minus(Duration.ofHours(1)), conversation("More work"));
        next = new ResponsesClient.Rejected(429, "HTTP 429 rate_limit_exceeded");
        summarizer().runOnce(NOW);
        var row = summary("s1");
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("summary")).isEqualTo("Fixed a failing payments test in billing-service.");
        assertThat(row.get("last_error")).isEqualTo("HTTP 429 rate_limit_exceeded");
        assertThat(jdbc.queryForList("SELECT state FROM openai_api_spend ORDER BY created_at", String.class)).containsExactly("settled", "failed");
        assertThat(summarizer().runOnce(NOW.plusSeconds(60))).isZero(); // backing off
        next = new IOException("reset");
        summarizer().runOnce(NOW.plus(Duration.ofMinutes(16)));
        assertThat(jdbc.queryForList("SELECT state FROM openai_api_spend ORDER BY created_at", String.class)).contains("unknown");
    }

    @Test void conversationsWithoutMemberTextAreSkippedWithoutCalls() {
        request("r1", "s1", null, "claude-opus", NOW.minus(Duration.ofHours(2)), "{\"messages\":[{\"role\":\"assistant\",\"content\":\"hi\"}]}");
        summarizer().runOnce(NOW);
        assertThat(calls).isEmpty();
        assertThat(summary("s1").get("status")).isEqualTo("skipped");
    }

    @Test void keyFileRequiresAnApiKeyShape() throws IOException {
        Files.writeString(keyFile, "not-a-key");
        assertThat(new OpenAIApiKey(keyFile.toString()).configured()).isFalse();
        assertThat(new OpenAIApiKey("").configured()).isFalse();
        assertThat(new OpenAIApiKey(keyFile.resolveSibling("missing").toString()).configured()).isFalse();
    }
}
