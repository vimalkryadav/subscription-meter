package dev.subscriptionmeter;

import dev.subscriptionmeter.portal.Account;
import dev.subscriptionmeter.portal.AccountRepository;
import dev.subscriptionmeter.portal.PortalAuthFilter;
import dev.subscriptionmeter.portal.SessionService;
import dev.subscriptionmeter.security.AdminAuthFilter;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.sql.init.mode=always",
        "spring.datasource.url=jdbc:h2:mem:virtual-key-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "meter.audit.timescale-enabled=false", "meter.mode=subscription"})
class VirtualKeyRequestFlowTest {
    private static final String MEMBER = "/portal/api/virtual-key-requests";
    private static final String ADMIN = "/admin/virtual-key-requests";
    private static final String BOOTSTRAP = "test-bootstrap-" + UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource static void secrets(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("virtual-key-flow-");
        Files.writeString(dir.resolve("admin"), BOOTSTRAP);
        Files.writeString(dir.resolve("metrics"), UUID.randomUUID().toString());
        Files.writeString(dir.resolve("upstream"), "");
        registry.add("meter.broker.admin-token-file", () -> dir.resolve("admin").toString());
        registry.add("meter.admin-token-file", () -> dir.resolve("admin").toString());
        registry.add("meter.metrics-token-file", () -> dir.resolve("metrics").toString());
        registry.add("meter.upstream-token-file", () -> dir.resolve("upstream").toString());
    }

    @Autowired WebApplicationContext context;
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
    @Autowired SessionService sessions;
    @Autowired PortalAuthFilter portalFilter;
    @Autowired AdminAuthFilter adminFilter;
    MockMvc mvc;

    @BeforeEach void setup() {
        new ResourceDatabasePopulator(new FileSystemResource("deploy/migrations/V014__virtual_key_requests.sql")).execute(source);
        jdbc.update("DELETE FROM virtual_key_requests");
        jdbc.update("UPDATE portal_accounts SET role='member' WHERE role='admin'");
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(portalFilter, adminFilter).build();
    }

    @Test void memberStatusIsPrivateAndApprovalOnlyDoesNotIssueAnything() throws Exception {
        Login member = login("member"), other = login("member"), admin = login("admin");
        assertThat(read(MEMBER, member).path("state").asString()).isEqualTo("none");
        JsonNode submitted = submit(member, "  Private project use case  ");
        String id = submitted.path("request").path("id").asString();
        assertThat(submitted.path("state").asString()).isEqualTo("pending");
        assertThat(submitted.path("request").path("reason").asString()).isEqualTo("Private project use case");
        assertThat(read(MEMBER, other).path("state").asString()).isEqualTo("none");
        assertThat(read(ADMIN, admin).get(0).path("username").asString()).isEqualTo(member.username());
        mvc.perform(write(MEMBER, member, "{\"reason\":\"duplicate\"}")).andExpect(status().isConflict());
        int members = jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class);
        var decision = mvc.perform(write(decision(id), admin, "{\"approve\":true}")).andExpect(status().isOk()).andReturn();
        assertThat(decision.getResponse().getContentAsString()).contains("approved_awaiting_provisioning").doesNotContain("token", "active", "key\"");
        assertThat(read(MEMBER, member).path("state").asString()).isEqualTo("approved_awaiting_provisioning");
        assertThat(read(MEMBER, member).path("request").path("decidedAt").isString()).isTrue();
        assertThat(read(ADMIN, admin).size()).isZero();
        var approved = mvc.perform(get(ADMIN).servletPath(ADMIN).cookie(admin.cookie())
                .param("state", "approved_awaiting_provisioning")).andExpect(status().isOk()).andReturn();
        assertThat(JSON.readTree(approved.getResponse().getContentAsString()).get(0).path("request").path("id").asString()).isEqualTo(id);
        mvc.perform(get(ADMIN).servletPath(ADMIN).cookie(admin.cookie()).param("state", "active")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class)).isEqualTo(members);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM admin_action_log WHERE target_id=? AND action='virtual_key_request.approve'", String.class, id)).isEqualTo(admin.id());
        assertThat(jdbc.queryForObject("SELECT details_json FROM admin_action_log WHERE target_id=?", String.class, id))
                .contains("approved_awaiting_provisioning").doesNotContain("Private project");
        mvc.perform(write(MEMBER, member, "{\"reason\":\"duplicate after approval\"}")).andExpect(status().isConflict());
    }

    @Test void authCsrfRoleAndInputBounds() throws Exception {
        mvc.perform(get(MEMBER).servletPath(MEMBER)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN).servletPath(ADMIN).header("Authorization", "Bearer " + BOOTSTRAP)).andExpect(status().isUnauthorized());
        Login member = login("member"), admin = login("admin");
        mvc.perform(get(ADMIN).servletPath(ADMIN).cookie(member.cookie())).andExpect(status().isForbidden());
        mvc.perform(get(MEMBER).servletPath(MEMBER).cookie(admin.cookie())).andExpect(status().isForbidden());
        mvc.perform(write(MEMBER, member, "{\"reason\":\"valid\"}").header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(post(MEMBER).servletPath(MEMBER).cookie(member.cookie()).header("X-Portal-Request", "1")
                .contentType("application/json").content("{\"reason\":\"valid\"}")).andExpect(status().isForbidden());
        for (String body : List.of("", "{}", "{\"reason\":7}", "{\"reason\":\"  \"}", "{\"reason\":\"secret\\u0000\"}",
                "{\"reason\":\"" + "X".repeat(501) + "\"}", "{\"reason\":\"good\",\"extra\":\"sensitive\"}",
                "{\"reason\":\"good\"}{}", "{\"reason\":\"good\",\"reason\":\"other\"}")) {
            var invalid = mvc.perform(write(MEMBER, member, body)).andExpect(status().isBadRequest()).andReturn();
            assertThat(invalid.getResponse().getContentAsString()).isEqualTo("{\"error\":\"Invalid form fields\"}");
        }
        mvc.perform(write(MEMBER, member, " ".repeat(4097))).andExpect(status().is(413));
        String path = decision(submit(member, "valid").path("request").path("id").asString());
        mvc.perform(write(path, member, "{\"approve\":true}")).andExpect(status().isForbidden());
        mvc.perform(post(path).servletPath(path).cookie(admin.cookie()).header("X-Portal-Request", "1")
                .contentType("application/json").content("{\"approve\":true}")).andExpect(status().isForbidden());
        mvc.perform(write(path, admin, "{\"approve\":\"false\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(path, admin, "{}")).andExpect(status().isBadRequest());
        mvc.perform(write(path, admin, " ".repeat(4097))).andExpect(status().is(413));
        assertThat(read(MEMBER, member).path("state").asString()).isEqualTo("pending");
    }

    @Test void rejectedRequestCanBeReplacedButStaleIdsCannotDecideReplacement() throws Exception {
        Login member = login("member"), admin = login("admin");
        String oldId = submit(member, "first").path("request").path("id").asString();
        mvc.perform(write(decision(oldId), admin, "{\"approve\":false}")).andExpect(status().isOk());
        assertThat(read(MEMBER, member).path("state").asString()).isEqualTo("rejected");
        mvc.perform(write(decision(oldId), admin, "{\"approve\":true}")).andExpect(status().isConflict());
        String newId = submit(member, "second").path("request").path("id").asString();
        assertThat(newId).isNotEqualTo(oldId);
        mvc.perform(write(decision(oldId), admin, "{\"approve\":true}")).andExpect(status().isNotFound());
        assertThat(read(MEMBER, member).path("state").asString()).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM virtual_key_requests", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentSubmissionsAndDecisionsHaveSingleWinners() throws Exception {
        Login member = login("member"), admin = login("admin");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var submits = executor.invokeAll(List.of(
                    () -> responseCode(write(MEMBER, member, "{\"reason\":\"first\"}")),
                    () -> responseCode(write(MEMBER, member, "{\"reason\":\"second\"}"))));
            assertThat(List.of(submits.get(0).get(), submits.get(1).get())).containsExactlyInAnyOrder(201, 409);
            String id = read(MEMBER, member).path("request").path("id").asString();
            var decisions = executor.invokeAll(List.of(
                    () -> responseCode(write(decision(id), admin, "{\"approve\":true}")),
                    () -> responseCode(write(decision(id), admin, "{\"approve\":false}"))));
            assertThat(List.of(decisions.get(0).get(), decisions.get(1).get())).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE target_id=?", Integer.class, id)).isEqualTo(1);
        }
    }

    @Test void adminQueueIsOldestFirstAndBounded() throws Exception {
        Login admin = login("admin");
        for (int i = 0; i < 53; i++) {
            Account account = account("member");
            jdbc.update("INSERT INTO virtual_key_requests (id, account_id, state, reason, requested_at) VALUES (?, ?, 'pending', ?, ?)",
                    UUID.randomUUID().toString(), account.id(), "request-" + i,
                    java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z").plusSeconds(i));
        }
        JsonNode rows = read(ADMIN, admin);
        assertThat(rows.size()).isEqualTo(50);
        assertThat(rows.get(0).path("request").path("reason").asString()).isEqualTo("request-0");
        assertThat(rows.get(49).path("request").path("reason").asString()).isEqualTo("request-49");
    }

    private record Login(String id, String username, Cookie cookie, String csrf) {}

    @Test void provisionsOnlyForActiveMembersAndRejectsRevokedOrInternalKeys() throws Exception {
        Login member = login("member"), other = login("member"), admin = login("admin");
        String id = submit(member, "Direct API testing").path("request").path("id").asString();
        String limits = "{\"weeklyBudgetUsd\":1,\"rpm\":5,\"allowedModels\":[\"gpt-6-luna\"]}";
        String provision = ADMIN + "/" + id + "/provision";
        mvc.perform(write(provision, member, limits)).andExpect(status().isForbidden());
        mvc.perform(write(provision, admin, limits)).andExpect(status().isConflict());
        String memberId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES(?,?,?,?,60,2)", memberId, "Test", memberId, "sm_test");
        jdbc.update("INSERT INTO portal_key_requests(id,account_id,state,reason,member_id) VALUES(?,?,'active','test',?)", UUID.randomUUID().toString(), member.id(), memberId);
        mvc.perform(write(decision(id), admin, "{\"approve\":true}")).andExpect(status().isOk());
        mvc.perform(write(provision, admin, limits)).andExpect(status().isOk());
        mvc.perform(write(provision, admin, limits)).andExpect(status().isConflict());
        assertThat(read(MEMBER, member).path("state").asString()).isEqualTo("active");
        String path = MEMBER + "/key";
        String key = read(path, member).path("key").asString();
        assertThat(key).startsWith("cuvk_");
        mvc.perform(get(path).servletPath(path).cookie(other.cookie())).andExpect(status().isNotFound());
        String models = "/api/v1/models";
        mvc.perform(get(models).servletPath(models)).andExpect(status().isUnauthorized());
        var upstream = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/responses", exchange -> {
            try (exchange) {
                String input = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (input.contains("sanitization-check")) {
                    byte[] failure = "{\"error\":\"synthetic-private-upstream-detail\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, failure.length);
                    exchange.getResponseBody().write(failure);
                    return;
                }
                byte[] body = "{\"status\":\"completed\",\"usage\":{\"input_tokens\":12,\"output_tokens\":5}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            }
        });
        upstream.createContext("/v1/embeddings", exchange -> {
            try (exchange) {
                var input = JSON.readTree(exchange.getRequestBody().readAllBytes());
                int count = input.path("input").isArray() ? input.path("input").size() : 1;
                var result = JSON.createObjectNode();
                result.put("object", "list");
                result.put("model", input.path("model").asString());
                var data = result.putArray("data");
                for (int index = 0; index < count; index++) {
                    var item = data.addObject();
                    item.put("object", "embedding"); item.put("index", index);
                    item.putArray("embedding").add(index).add(input.path("dimensions").asInt());
                }
                result.putObject("usage").put("prompt_tokens", 6).put("total_tokens", 6);
                byte[] bytes = JSON.writeValueAsBytes(result);
                int status = "Bearer test-key".equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
                if (input.path("user").asString("").equals("rate-test")) {
                    status = 429; exchange.getResponseHeaders().set("Retry-After", "7");
                }
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        upstream.start();
        var store = new dev.subscriptionmeter.broker.BrokerStore(jdbc, () -> java.util.Optional.of(BOOTSTRAP));
        var broker = new dev.subscriptionmeter.broker.BrokerServer(store, () -> java.util.Optional.of("test-key"),
                java.util.Optional::empty, "http://127.0.0.1:" + upstream.getAddress().getPort(), java.time.Clock.systemUTC());
        try {
            int port = broker.start("127.0.0.1", 0).getPort();
            var controller = new dev.subscriptionmeter.web.DirectApiController(context.getBean(dev.subscriptionmeter.portal.DirectVirtualKeys.class),
                    new dev.subscriptionmeter.deployment.DeploymentState(), "http://127.0.0.1:" + port);
            try {
                var direct = MockMvcBuilders.standaloneSetup(controller).build();
                direct.perform(get(models).header("Authorization", "Bearer " + key)).andExpect(status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].id").value("gpt-6-luna"));
                String responses = "/api/v1/responses";
                direct.perform(post(responses).header("Authorization", "Bearer " + key).contentType("application/json")
                        .content("{\"model\":\"gpt-6-luna\",\"input\":\"Hello\"}")).andExpect(status().isOk());
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM broker_calls WHERE member_id=? AND purpose='member_direct' AND state='settled'", Integer.class, memberId)).isEqualTo(1);
                direct.perform(post(responses).header("Authorization", "Bearer " + key).contentType("application/json")
                        .content("{\"model\":\"gpt-5-mini\",\"input\":\"Hello\"}")).andExpect(status().isBadRequest());
                direct.perform(post(responses).header("Authorization", "Bearer " + key).contentType("application/json")
                        .content("{\"model\":\"gpt-6-luna\",\"input\":\"Hello\",\"background\":true}")).andExpect(status().isBadRequest());
                var refused = direct.perform(post(responses).header("Authorization", "Bearer " + key).contentType("application/json")
                        .content("{\"model\":\"gpt-6-luna\",\"input\":\"sanitization-check\"}")).andExpect(status().isBadRequest()).andReturn();
                assertThat(refused.getResponse().getContentAsString()).contains("request_rejected").doesNotContain("synthetic-private-upstream-detail");
                String embedding = "/api/v1/embeddings";
                String batch = "{\"model\":\"text-embedding-3-small\",\"input\":[\"first\",\"second\"],\"dimensions\":1536}";
                direct.perform(post(embedding).contentType("application/json").content(batch)).andExpect(status().isUnauthorized());
                var response = direct.perform(post(embedding).header("Authorization", "Bearer " + key)
                    .contentType("application/json").content(batch)).andExpect(status().isOk()).andReturn();
                var vectors = JSON.readTree(response.getResponse().getContentAsString()).path("data");
                assertThat(vectors.size()).isEqualTo(2);
                assertThat(vectors.get(1).path("index").asInt()).isEqualTo(1);
                assertThat(vectors.get(0).path("embedding").get(1).asInt()).isEqualTo(1536);
                String usagePath = MEMBER + "/embedding-usage";
                var usage = read(usagePath, member).get(0);
                assertThat(usage.path("items").asInt()).isEqualTo(2);
                assertThat(usage.path("promptTokens").asInt()).isEqualTo(6);
                assertThat(usage.path("totalTokens").asInt()).isEqualTo(6);
                assertThat(read(usagePath, other).isEmpty()).isTrue();
                direct.perform(post(embedding).header("Authorization", "Bearer " + key).contentType("application/json")
                    .content(batch.replace("text-embedding-3-small", "text-embedding-3-large"))).andExpect(status().isBadRequest());
                direct.perform(post(embedding).header("Authorization", "Bearer " + key).contentType("application/json")
                    .content("{\"model\":\"text-embedding-3-small\",\"input\":\"one\",\"user\":\"rate-test\"}"))
                    .andExpect(status().isTooManyRequests()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "7"));
                // Two Responses attempts and two embeddings attempts consume the same RPM counter.
                jdbc.update("UPDATE broker_keys SET rpm=4 WHERE member_id=?", memberId);
                var limited = direct.perform(post(embedding).header("Authorization", "Bearer " + key)
                    .contentType("application/json").content(batch)).andExpect(status().isTooManyRequests()).andReturn();
                assertThat(limited.getResponse().getHeader("Retry-After")).isNull();
            } finally { controller.close(); }
        } finally { broker.stop(0); upstream.stop(0); }
        jdbc.update("UPDATE broker_keys SET purpose='member_fallback' WHERE member_id=?", memberId);
        mvc.perform(get(models).servletPath(models).header("Authorization", "Bearer " + key)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE broker_keys SET purpose='member_direct' WHERE member_id=?", memberId);
        mvc.perform(write(ADMIN + "/" + id + "/revoke", admin, "{}")).andExpect(status().isOk());
        mvc.perform(get(models).servletPath(models).header("Authorization", "Bearer " + key)).andExpect(status().isUnauthorized());
        assertThat(read(MEMBER, member).path("state").asString()).isEqualTo("revoked");
    }

    private Account account(String role) {
        String id = UUID.randomUUID().toString();
        var account = new Account(id, "test-" + id.replace("-", ""), "Virtual Key Tester", "unused test password hash", role);
        accounts.add(account);
        if (role.equals("admin")) jdbc.update("UPDATE portal_accounts SET role=? WHERE id=?", role, id);
        return account;
    }

    private Login login(String role) {
        Account account = account(role);
        String raw = sessions.createLegacy(account.id(), null, null);
        return new Login(account.id(), account.username(), new Cookie(PortalAuthFilter.COOKIE, raw), sessions.csrf(raw));
    }

    private JsonNode read(String path, Login login) throws Exception {
        return JSON.readTree(mvc.perform(get(path).servletPath(path).cookie(login.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode submit(Login login, String reason) throws Exception {
        return JSON.readTree(mvc.perform(write(MEMBER, login, JSON.writeValueAsString(java.util.Map.of("reason", reason))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static String decision(String id) { return ADMIN + "/" + id + "/decision"; }

    private static MockHttpServletRequestBuilder write(String path, Login login, String body) {
        return post(path).servletPath(path).cookie(login.cookie()).header("X-Portal-Request", "1")
                .header("X-CSRF-Token", login.csrf()).contentType("application/json").content(body);
    }

    private int responseCode(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
}
