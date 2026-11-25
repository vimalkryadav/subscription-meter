package dev.subscriptionmeter;

import dev.subscriptionmeter.portal.*;
import dev.subscriptionmeter.security.AdminAuthFilter;
import dev.subscriptionmeter.security.MemberService;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:usageoverview;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.mode=always",
        "meter.audit.timescale-enabled=false", "meter.mode=subscription"})
@Transactional
class UsageOverviewTest {
    private static final String ADMIN = "/admin/usage-overview", PORTAL = "/portal/api/usage-overview";
    private static final String BOOTSTRAP_TOKEN = "synthetic-bootstrap-" + UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource static void credentials(DynamicPropertyRegistry props) throws Exception {
        Path dir = Files.createTempDirectory("usage-overview-test-");
        for (String name : List.of("admin", "metrics", "upstream")) {
            Files.writeString(dir.resolve(name), name.equals("admin") ? BOOTSTRAP_TOKEN : "synthetic-" + UUID.randomUUID());
            props.add("meter." + name + "-token-file", () -> dir.resolve(name).toString());
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accounts;
    @Autowired SessionService sessions;
    @Autowired MemberService members;
    @Autowired WebApplicationContext context;
    @Autowired PortalAuthFilter portalFilter;
    @Autowired AdminAuthFilter adminFilter;
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(portalFilter, adminFilter).build();
    }

    @Test void filtersRequireSignInAndCurrentAdminRole() throws Exception {
        mvc.perform(get(PORTAL).servletPath(PORTAL)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN).servletPath(ADMIN)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN).servletPath(ADMIN).header("Authorization", "Bearer " + BOOTSTRAP_TOKEN))
                .andExpect(status().isUnauthorized());
        var owner = accounts.register("usage-owner", "Private Owner", "a strong test password");
        var ownerCookie = cookie(owner);
        mvc.perform(get(ADMIN).servletPath(ADMIN).cookie(ownerCookie)).andExpect(status().isForbidden());
        jdbc.update("UPDATE portal_accounts SET role='admin' WHERE id=?", owner.id());
        mvc.perform(get(ADMIN).servletPath(ADMIN).cookie(ownerCookie)).andExpect(status().isOk());
        for (String endpoint : List.of(ADMIN, PORTAL)) {
            for (String invalid : List.of("", "1", "31", "garbage")) {
                mvc.perform(get(endpoint).servletPath(endpoint).cookie(ownerCookie).param("days", invalid))
                        .andExpect(status().isBadRequest());
            }
        }
        jdbc.update("UPDATE portal_accounts SET role='member' WHERE id=?", owner.id());
        mvc.perform(get(ADMIN).servletPath(ADMIN).cookie(ownerCookie)).andExpect(status().isForbidden());
        sessions.logout(ownerCookie.getValue());
        mvc.perform(get(PORTAL).servletPath(PORTAL).cookie(ownerCookie)).andExpect(status().isUnauthorized());
    }

    @Test void aggregatesOnlyGatewayMessagesAndRanksByDisplayName() throws Exception {
        var viewer = accounts.register("usage-viewer", "Private Viewer", "a strong test password");
        var viewerCookie = cookie(viewer);
        var admin = accounts.register("usage-admin", "Private Admin", "a strong test password");
        jdbc.update("UPDATE portal_accounts SET role='admin' WHERE id=?", admin.id());
        var adminCookie = cookie(admin);
        String mine = member("Private Gateway Member");
        String peer = member("Private Peer");
        String revoked = member("Private Revoked Member");
        jdbc.update("UPDATE members SET active=FALSE WHERE id=?", revoked);
        jdbc.update("INSERT INTO portal_key_requests(id,account_id,state,reason,member_id) VALUES(?,?,'active','test',?)",
                UUID.randomUUID().toString(), viewer.id(), mine);
        var peerAccount = accounts.register("usage-peer", "Peer Display", "a strong test password");
        jdbc.update("INSERT INTO portal_key_requests(id,account_id,state,reason,member_id) VALUES(?,?,'active','test',?)",
                UUID.randomUUID().toString(), peerAccount.id(), peer);
        jdbc.update("INSERT INTO routing_deployments(id,name,kind,account,priority,weight,enabled,updated_by) "
                + "VALUES('codex-extra','Private Extra Deployment','codex','extra-account',1,100,FALSE,'test')");
        message(mine, "/v1/messages", "anthropic", "primary", 1, 10);
        message(mine, "/v1/messages", "openai", "codex", 1, 0); // upstream failure still counts once
        message(mine, "/v1/messages", "openai", "extra-account", 1, 20);
        message(mine, "/v1/messages", "openai", "broker", 1, 30);
        message(mine, "/v1/messages", "openai", "unknown-account", 1, 40);
        message(mine, "/v1/messages", "openai", "broker", 10, 100);
        message(mine, "/v1/messages", "openai", "broker", 31, 1000);
        message(mine, "/v1/messages", "openai", "broker", -1, 1000);
        message(mine, "/v1/messages/count_tokens", "anthropic", "primary", 1, 1000);
        message(mine, "/v1/responses", "openai", "primary", 1, 1000);
        message(peer, "/v1/messages", "anthropic", "primary", 1, 300);
        message(revoked, "/v1/messages", "anthropic", "primary", 1, 500);
        jdbc.update("""
            INSERT INTO openai_api_spend(id,purpose,reference_id,model,state,reserved_usd,cost_usd,input_tokens,output_tokens)
            VALUES(?,'session_summary',?,'synthetic-model','settled',1,1,1000,1000)
            """, UUID.randomUUID().toString(), mine + ":private-session");

        String portal = read(PORTAL, viewerCookie, null);
        assertThat(JSON.readTree(portal)).isEqualTo(JSON.readTree("""
            {"days":7,"own":[{"tier":"claude","requests":1,"tokens":10},
            {"tier":"codex","requests":2,"tokens":20},{"tier":"paid","requests":1,"tokens":30},
            {"tier":"other","requests":1,"tokens":40}],"leaderboard":[
            {"label":"Peer Display","requests":1,"tokens":300,"you":false},
            {"label":"Private Viewer","requests":5,"tokens":100,"you":true}]}
            """));
        assertThat(portal).doesNotContain(mine, peer, revoked, viewer.id(), peerAccount.id(), "usage-peer", "usage-viewer", "Private Gateway Member", "Private Peer", "broker", "cost", "capture");
        var longer = JSON.readTree(read(PORTAL, viewerCookie, "30"));
        assertThat(longer.path("days").asInt()).isEqualTo(30);
        assertThat(longer.path("own").get(2).path("tokens").asInt()).isEqualTo(130);
        assertThat(longer.path("leaderboard").get(1).path("requests").asInt()).isEqualTo(6);
        assertThat(JSON.readTree(read(ADMIN, adminCookie, null))).isEqualTo(JSON.readTree("""
            {"days":7,"own":[{"tier":"claude","requests":3,"tokens":810},
            {"tier":"codex","requests":2,"tokens":20},{"tier":"paid","requests":1,"tokens":30},
            {"tier":"other","requests":1,"tokens":40}],"leaderboard":[
            {"label":"Member 1","requests":1,"tokens":500,"you":false},
            {"label":"Peer Display","requests":1,"tokens":300,"you":false},
            {"label":"Private Viewer","requests":5,"tokens":100,"you":false}]}
            """));

        jdbc.update("UPDATE members SET active=FALSE WHERE id=?", mine);
        var inactive = JSON.readTree(read(PORTAL, viewerCookie, null));
        assertThat(inactive.path("leaderboard").size()).isZero();
        assertThat(inactive.path("own")).isEqualTo(JSON.readTree(portal).path("own"));
        jdbc.update("UPDATE members SET active=TRUE WHERE id=?", mine);
        jdbc.update("UPDATE portal_key_requests SET state='revoked' WHERE account_id=?", viewer.id());
        assertThat(JSON.readTree(read(PORTAL, viewerCookie, null))).isEqualTo(inactive);
        assertThat(JSON.readTree(read(PORTAL, adminCookie, null))).isEqualTo(JSON.readTree("""
            {"days":7,"own":[{"tier":"claude","requests":0,"tokens":0},
            {"tier":"codex","requests":0,"tokens":0},{"tier":"paid","requests":0,"tokens":0},
            {"tier":"other","requests":0,"tokens":0}],"leaderboard":[]}
            """));
    }

    private Cookie cookie(Account account) { return new Cookie(PortalAuthFilter.COOKIE, sessions.create(account.id(), null)); }
    private String member(String name) { return members.create(name, 60, 2).member().id(); }

    private String read(String path, Cookie cookie, String days) throws Exception {
        var request = get(path).servletPath(path).cookie(cookie);
        if (days != null) request.param("days", days);
        return mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
    }

    private void message(String member, String path, String provider, String account, int daysAgo, int tokens) {
        int quarter = tokens / 4;
        jdbc.update("""
            INSERT INTO requests(id,member_id,started_at,path,model,provider,upstream_account,state,status_code,
                 input_tokens,output_tokens,cache_creation_input_tokens,cache_read_input_tokens)
            VALUES(?,?,?,?,'synthetic-model',?,?,?,?,?,?,?,?)
            """, UUID.randomUUID().toString(), member, Timestamp.from(Instant.now().minusSeconds(86400L * daysAgo)),
                path, provider, account, tokens > 0 ? "complete" : "upstream_error", tokens > 0 ? 200 : 502,
                tokens - 3 * quarter, quarter, quarter, quarter);
    }
}
