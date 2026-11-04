package dev.subscriptionmeter;

import dev.subscriptionmeter.portal.*;
import dev.subscriptionmeter.security.*;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.sql.init.mode=always","spring.datasource.url=jdbc:h2:mem:portal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa","spring.datasource.password=","meter.audit.timescale-enabled=false","meter.mode=subscription"})
class PortalFlowTest {
    private static final String ADMIN="test-admin-"+UUID.randomUUID();
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @DynamicPropertySource static void secrets(DynamicPropertyRegistry r) throws Exception {
        Path dir=Files.createTempDirectory("portal-test-");
        Files.writeString(dir.resolve("admin"),ADMIN);Files.writeString(dir.resolve("metrics"),"metrics-"+UUID.randomUUID());Files.writeString(dir.resolve("upstream"),"");
        r.add("meter.admin-token-file",()->dir.resolve("admin").toString());
        r.add("meter.metrics-token-file",()->dir.resolve("metrics").toString());
        r.add("meter.upstream-token-file",()->dir.resolve("upstream").toString());
    }
    @Autowired WebApplicationContext context;
    @Autowired PortalAuthFilter portalFilter;
    @Autowired AdminAuthFilter adminFilter;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberService members;
    @Autowired PortalAccessService access;
    @Autowired SessionService sessions;
    @Autowired AccountService accounts;
    MockMvc mvc;
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).servletPath(path);
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).servletPath(path);
    }
    record Login(String id,String username,Cookie cookie,String csrf) {}
    @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(portalFilter,adminFilter).build();}
    JsonNode json(MvcResult result) throws Exception{return JSON.readTree(result.getResponse().getContentAsString());}
    Login register() throws Exception {
        String username="u"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var result=mvc.perform(post("/portal/api/register").contentType("application/json").header("X-Portal-Request","1")
                .content(JSON.writeValueAsString(Map.of("username",username,"displayName","Portal Tester","password","a strong test password")))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("HttpOnly","Secure","SameSite=Strict","Path=/");
        var body=json(result);
        return new Login(body.path("user").path("id").asString(),username,result.getResponse().getCookie("meter_session"),body.path("csrfToken").asString());
    }
    MvcResult write(Login login,String path,Object body) throws Exception {
        return mvc.perform(post("/portal/api"+path).cookie(login.cookie()).header("X-Portal-Request","1").header("X-CSRF-Token",login.csrf())
                .contentType("application/json").content(JSON.writeValueAsString(body))).andReturn();
    }
    String request(Login login) throws Exception {
        var result=write(login,"/key-requests",Map.of("reason","Test gateway access"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result).path("access").path("request").path("id").asString();
    }
    void approve(String id) throws Exception {
        var result=mvc.perform(post("/admin/key-requests/"+id+"/decision").header("Authorization","Bearer "+ADMIN).contentType("application/json")
                .content("{\"approve\":true,\"rpm\":60,\"concurrency\":4}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }
    @Test void registrationApprovalIssueRotationAndRevocation() throws Exception {
        Login user=register();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM portal_accounts WHERE id=?",String.class,user.id())).doesNotContain("a strong test password");
        assertThat(write(user,"/token",Map.of()).getResponse().getStatus()).isEqualTo(409);
        String id=request(user);
        assertThat(write(user,"/key-requests",Map.of("reason","duplicate")).getResponse().getStatus()).isEqualTo(409);
        assertThat(write(user,"/token",Map.of()).getResponse().getStatus()).isEqualTo(409);
        approve(id);
        var issued=write(user,"/token",Map.of());assertThat(issued.getResponse().getStatus()).isEqualTo(200);
        String token=json(issued).path("key").asString(),memberId=members.authenticate(token).id();
        assertThat(write(user,"/token",Map.of()).getResponse().getStatus()).isEqualTo(409);
        String rotated=json(write(user,"/token/rotate",Map.of())).path("key").asString();
        assertThat(members.authenticate(rotated).id()).isEqualTo(memberId);
        assertThatThrownBy(()->members.authenticate(token)).hasMessageContaining("401");
        assertThat(jdbc.queryForObject("SELECT key_hash FROM members WHERE id=?",String.class,memberId)).doesNotContain(rotated);
        members.revoke(memberId); // Legacy admin revocation must also block portal rotation.
        assertThat(access.status(user.id()).get("state")).isEqualTo("revoked");
        assertThat(write(user,"/token/rotate",Map.of()).getResponse().getStatus()).isEqualTo(409);
        request(user);approve(id);
        String replacement=json(write(user,"/token",Map.of())).path("key").asString();
        assertThat(members.authenticate(replacement).id()).isEqualTo(memberId);
        access.revoke(id);
        assertThatThrownBy(()->members.authenticate(replacement)).hasMessageContaining("401");
    }
    @Test void cookieCsrfOwnershipAndAdminBoundaries() throws Exception {
        Login a=register(),b=register();String id=request(a);approve(id);
        String key=json(write(a,"/token",Map.of())).path("key").asString();
        assertThat(mvc.perform(get("/portal/api/access")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/portal/api/access").header("Authorization","Bearer "+key)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/portal/api/token/rotate").cookie(a.cookie()).header("X-Portal-Request","1")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/portal/api/token/rotate").cookie(a.cookie()).header("X-Portal-Request","1").header("X-CSRF-Token",a.csrf()).header("Sec-Fetch-Site","cross-site")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/portal/api/login").contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/admin/key-requests").cookie(a.cookie())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/admin/key-requests").header("Authorization","Bearer "+key)).andReturn().getResponse().getStatus()).isEqualTo(401);
        var own=mvc.perform(get("/portal/api/access").cookie(b.cookie()).param("accountId",a.id())).andReturn();
        assertThat(json(own).path("access").path("state").asString()).isEqualTo("none");
        assertThat(own.getResponse().getContentAsString()).doesNotContain(a.username(),key);
        assertThat(write(b,"/token",Map.of("accountId",a.id())).getResponse().getStatus()).isEqualTo(409);
        assertThat(write(a,"/logout",Map.of()).getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/portal/api/session").cookie(a.cookie())).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
    @Test void concurrentClaimsIssueExactlyOneKey() throws Exception {
        Login user=register();approve(request(user));
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> calls=new ArrayList<>();
            for(int i=0;i<4;i++) calls.add(()->{try{access.issue(user.id(),false);return true;}catch(org.springframework.web.server.ResponseStatusException e){assertThat(e.getStatusCode().value()).isEqualTo(409);return false;}});
            int successes=0;for(var future:pool.invokeAll(calls)) if(future.get())successes++;
            assertThat(successes).isEqualTo(1);
        }
    }
    @Test void passwordLoginSessionReplacementAndExpiry() throws Exception {
        Login user=register();
        assertThatThrownBy(()->accounts.login(user.username(),"wrong password")).hasMessageContaining("401");
        assertThat(accounts.login(user.username().toUpperCase(Locale.ROOT),"a strong test password").id()).isEqualTo(user.id());
        assertThatThrownBy(()->accounts.register(user.username(),"Duplicate","a strong test password")).hasMessageContaining("409");
        String raw=sessions.create(user.id(),user.cookie().getValue());
        assertThatThrownBy(()->sessions.authenticate(user.cookie().getValue())).hasMessageContaining("401");
        assertThat(sessions.authenticate(raw)).isEqualTo(user.id());
        jdbc.update("UPDATE portal_sessions SET last_seen_at=? WHERE account_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(3601)),user.id());
        assertThatThrownBy(()->sessions.authenticate(raw)).hasMessageContaining("401");
        String next=sessions.create(user.id(),null);
        jdbc.update("UPDATE portal_sessions SET expires_at=? WHERE account_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),user.id());
        assertThatThrownBy(()->sessions.authenticate(next)).hasMessageContaining("401");
    }
    @Test void adminRoleControlsAccessAndDisablesBootstrapKey() throws Exception {
        Login owner=register(),member=register();
        jdbc.update("UPDATE portal_accounts SET role='admin' WHERE id=?",owner.id());
        try {
            assertThat(mvc.perform(get("/admin/status").header("Authorization","Bearer "+ADMIN)).andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(get("/admin/status").cookie(owner.cookie())).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(mvc.perform(get("/admin/status").cookie(member.cookie())).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(get("/admin/metrics")).andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(get("/admin/metrics").cookie(member.cookie())).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(get("/admin/metrics").cookie(owner.cookie())).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(mvc.perform(get("/admin/metrics").cookie(owner.cookie()).param("hours","10000")).andReturn().getResponse().getStatus()).isEqualTo(400);
            assertThat(mvc.perform(get("/admin/metrics").cookie(owner.cookie()).param("mode","invalid")).andReturn().getResponse().getStatus()).isEqualTo(400);
            assertThat(mvc.perform(get("/admin/metrics").cookie(owner.cookie()).param("mode","mock")).andReturn().getResponse().getStatus()).isEqualTo(400);
            assertThat(json(mvc.perform(get("/portal/api/session").cookie(owner.cookie())).andReturn()).path("user").path("role").asString()).isEqualTo("admin");
            assertThat(mvc.perform(post("/admin/members").cookie(owner.cookie()).contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
            var created=mvc.perform(post("/admin/members").cookie(owner.cookie()).header("X-Portal-Request","1").header("X-CSRF-Token",owner.csrf())
                    .contentType("application/json").content("{\"name\":\"role-test\",\"rpm\":10,\"concurrency\":1}")).andReturn();
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            members.revoke(json(created).path("member").path("id").asString());
            jdbc.update("UPDATE portal_accounts SET role='member' WHERE id=?",owner.id());
            assertThat(mvc.perform(get("/admin/status").cookie(owner.cookie())).andReturn().getResponse().getStatus()).isEqualTo(403);
        } finally { jdbc.update("UPDATE portal_accounts SET role='member' WHERE id=?",owner.id()); }
    }
    @Test void dashboardDocumentRequiresAnActiveAdminSession() throws Exception {
        for(String path:List.of("/manage","/manage/")) {
            var signedOut=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andReturn().getResponse();
            assertThat(signedOut.getStatus()).isEqualTo(303);
            assertThat(signedOut.getHeader("Location")).isEqualTo("/");
            assertThat(signedOut.getContentAsString()).doesNotContain("metricsData");
        }
        Login owner=register(),member=register();
        jdbc.update("UPDATE portal_accounts SET role='admin' WHERE id=?",owner.id());
        try {
            assertThat(mvc.perform(get("/manage").cookie(member.cookie())).andReturn().getResponse().getStatus()).isEqualTo(403);
            var page=mvc.perform(get("/manage").cookie(owner.cookie())).andReturn().getResponse();
            assertThat(page.getStatus()).isEqualTo(200);
            assertThat(page.getHeader("Cache-Control")).contains("no-store");
            assertThat(page.getContentAsString()).contains("metricsData","control-usage");
            assertThat(mvc.perform(get("/manage.html")).andReturn().getResponse().getStatus()).isEqualTo(404);
            sessions.logout(owner.cookie().getValue());
            assertThat(mvc.perform(get("/manage").cookie(owner.cookie())).andReturn().getResponse().getStatus()).isEqualTo(303);
            assertThat(mvc.perform(get("/manage").cookie(new Cookie("meter_session","invalid"))).andReturn().getResponse().getStatus()).isEqualTo(303);
        } finally { jdbc.update("UPDATE portal_accounts SET role='member' WHERE id=?",owner.id()); }
    }
    @Test void rejectionCanBeResubmittedAndLimitsAreValidated() throws Exception {
        Login user=register();String id=request(user);
        access.decide(id,false,60,4);
        assertThat(write(user,"/token",Map.of()).getResponse().getStatus()).isEqualTo(409);
        request(user);
        var invalid=mvc.perform(post("/admin/key-requests/"+id+"/decision").header("Authorization","Bearer "+ADMIN)
                .contentType("application/json").content("{\"approve\":true,\"rpm\":0,\"concurrency\":21}")).andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(access.status(user.id()).get("state")).isEqualTo("pending");
        for(int i=0;i<10;i++) {
            try{accounts.login("nonexistent-test-user","wrong");}catch(org.springframework.web.server.ResponseStatusException ignored){}
        }
        assertThatThrownBy(()->accounts.login("nonexistent-test-user","wrong")).hasMessageContaining("429");
    }
}
