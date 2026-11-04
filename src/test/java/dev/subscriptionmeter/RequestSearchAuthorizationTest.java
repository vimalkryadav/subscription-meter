package dev.subscriptionmeter;

import dev.subscriptionmeter.portal.*;
import dev.subscriptionmeter.security.AdminAuthFilter;
import dev.subscriptionmeter.security.MemberService;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:searchauth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa","spring.datasource.password=","spring.sql.init.mode=always","meter.audit.timescale-enabled=false","meter.mode=subscription"})
class RequestSearchAuthorizationTest {
    @DynamicPropertySource static void testCredentials(DynamicPropertyRegistry registry) throws Exception {
        Path dir=Files.createTempDirectory("request-search-test-");
        for(String name:List.of("admin","metrics","upstream"))Files.writeString(dir.resolve(name),"synthetic-test-"+UUID.randomUUID());
        registry.add("meter.admin-token-file",()->dir.resolve("admin").toString());
        registry.add("meter.metrics-token-file",()->dir.resolve("metrics").toString());
        registry.add("meter.upstream-token-file",()->dir.resolve("upstream").toString());
    }
    @Autowired WebApplicationContext context; @Autowired AdminAuthFilter adminFilter;
    @Autowired AccountService accounts; @Autowired SessionService sessions; @Autowired JdbcTemplate jdbc;
    @Autowired MemberService members;
    @Test void searchDetailAndExportRequireCurrentAdminAuthority() throws Exception {
        var owner=accounts.register("owner"+UUID.randomUUID().toString().substring(0,8),"Owner","a strong test password");
        var member=accounts.register("member"+UUID.randomUUID().toString().substring(0,8),"Member","a strong test password");
        var ownerCookie=new Cookie("meter_session",sessions.create(owner.id(),null));
        var memberCookie=new Cookie("meter_session",sessions.create(member.id(),null));
        jdbc.update("UPDATE portal_accounts SET role='admin' WHERE id=?",owner.id());
        var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(adminFilter).build();
        try{
            for(String path:List.of("/admin/request-search","/admin/request-search/missing","/admin/requests/missing/export")){
                assertThat(mvc.perform(get(path).servletPath(path)).andReturn().getResponse().getStatus()).isEqualTo(401);
                assertThat(mvc.perform(get(path).servletPath(path).cookie(memberCookie)).andReturn().getResponse().getStatus()).isEqualTo(403);
                assertThat(mvc.perform(get(path).servletPath(path).cookie(ownerCookie)).andReturn().getResponse().getStatus()).isEqualTo(path.equals("/admin/request-search")?200:404);
            }
            sessions.logout(ownerCookie.getValue());
            assertThat(mvc.perform(get("/admin/request-search").servletPath("/admin/request-search").cookie(ownerCookie)).andReturn().getResponse().getStatus()).isEqualTo(401);
        }finally{jdbc.update("UPDATE portal_accounts SET role='member' WHERE id=?",owner.id());}
    }
    @Test void adminSessionRevocationAndLimitEditsRequireCsrfAndNeverReturnCredentials() throws Exception {
        var owner=accounts.register("owner"+UUID.randomUUID().toString().substring(0,8),"Owner","a strong test password");
        var user=accounts.register("target"+UUID.randomUUID().toString().substring(0,8),"Target","a strong test password");
        String ownerToken=sessions.create(owner.id(),null),userToken=sessions.create(user.id(),null);
        String userHash=jdbc.queryForObject("SELECT token_hash FROM portal_sessions WHERE account_id=?",String.class,user.id());
        var cookie=new Cookie("meter_session",ownerToken);jdbc.update("UPDATE portal_accounts SET role='admin' WHERE id=?",owner.id());
        var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(adminFilter).build();
        var key=members.create("Limit target",60,4);String limits="/admin/members/"+key.member().id()+"/limits";
        String body="{\"rpm\":20,\"concurrency\":2,\"expectedRpm\":60,\"expectedConcurrency\":4}";
        try {
            assertThat(mvc.perform(patch(limits).servletPath(limits).cookie(cookie).contentType("application/json").content(body)).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(patch(limits).servletPath(limits).cookie(cookie).header("X-Portal-Request","1").header("X-CSRF-Token",PortalTokens.csrf(ownerToken)).contentType("application/json").content(body)).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(members.authenticate(key.key()).rpm()).isEqualTo(20); // Existing key remains usable.
            assertThat(mvc.perform(patch(limits).servletPath(limits).cookie(cookie).header("X-Portal-Request","1").header("X-CSRF-Token",PortalTokens.csrf(ownerToken)).contentType("application/json").content(body)).andReturn().getResponse().getStatus()).isEqualTo(409);
            String invoices="/admin/subscription-invoices";
            String invoice="{\"provider\":\"anthropic\",\"upstreamAccount\":\"primary\",\"periodStart\":\"2026-11-04T00:00:00Z\",\"periodEnd\":\"2026-11-05T00:00:00Z\",\"amount\":50,\"currency\":\"INR\",\"gatewayFraction\":0.5,\"allocationBasis\":\"equal\",\"provenance\":\"estimated_subscription\"}";
            assertThat(mvc.perform(post(invoices).servletPath(invoices).cookie(cookie).contentType("application/json").content(invoice)).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(get(invoices).servletPath(invoices).cookie(new Cookie("meter_session",userToken))).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(post(invoices).servletPath(invoices).cookie(cookie).header("X-Portal-Request","1").header("X-CSRF-Token",PortalTokens.csrf(ownerToken)).contentType("application/json").content(invoice)).andReturn().getResponse().getStatus()).isEqualTo(201);
            String requestId=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO requests(id,member_id,path,model,state) VALUES(?,?,'/v1/messages','test','complete')",requestId,key.member().id());
            String classification="/admin/requests/"+requestId+"/classification";
            assertThat(mvc.perform(patch(classification).servletPath(classification).cookie(cookie).contentType("application/json").content("{\"category\":\"testing\"}")).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(patch(classification).servletPath(classification).cookie(cookie).header("X-Portal-Request","1").header("X-CSRF-Token",PortalTokens.csrf(ownerToken)).contentType("application/json").content("{\"category\":\"testing\"}")).andReturn().getResponse().getStatus()).isEqualTo(200);
            String path="/admin/accounts/"+user.id()+"/sessions";
            var response=mvc.perform(get(path).servletPath(path).cookie(cookie)).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).doesNotContain(userToken,userHash,"token_hash","password_hash");
            String ref=JsonMapper.builder().build().readTree(response.getContentAsString()).get(0).path("sessionRef").asString();
            assertThat(mvc.perform(delete(path+"/"+ref).servletPath(path+"/"+ref).cookie(cookie)).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(delete(path+"/"+ref).servletPath(path+"/"+ref).cookie(cookie).header("X-Portal-Request","1").header("X-CSRF-Token",PortalTokens.csrf(ownerToken))).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThatThrownBy(()->sessions.authenticate(userToken)).hasMessageContaining("401");
            var log=mvc.perform(get("/admin/action-log").servletPath("/admin/action-log").cookie(cookie)).andReturn().getResponse();
            assertThat(log.getContentAsString()).contains("member.limits.update","sessions.revoke").doesNotContain(key.key(),userToken,userHash);
            assertThat(mvc.perform(get("/admin/accounts").servletPath("/admin/accounts").cookie(cookie)).andReturn().getResponse().getContentAsString()).doesNotContain("password_hash",owner.passwordHash(),user.passwordHash());
        }finally{jdbc.update("UPDATE portal_accounts SET role='member' WHERE id=?",owner.id());}
    }
}
