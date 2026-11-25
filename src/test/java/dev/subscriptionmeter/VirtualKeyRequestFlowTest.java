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
        mvc.perform(get(ADMIN).servletPath(ADMIN).cookie(admin.cookie()).param("state", "active")).andExpect(status().isBadRequest());
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
