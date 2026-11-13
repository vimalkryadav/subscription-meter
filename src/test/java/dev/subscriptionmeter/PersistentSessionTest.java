package dev.subscriptionmeter;

import dev.subscriptionmeter.portal.*;
import dev.subscriptionmeter.administration.JdbcAdminOperations;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;

/** Real transaction proxies: replay rejection must commit revocation, including on PostgreSQL. */
class PersistentSessionTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true) static class Transactions {
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
    }
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;SessionService sessions;JdbcAdminOperations admin;
    DriverManagerDataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:refresh"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
    @BeforeEach void setup(){
        var ds=dataSource();new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("accounting-schema.sql")).execute(ds);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(DataSource.class,()->ds);context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(ds));
        context.register(JdbcPortalRepository.class,RefreshSessionRepository.class,SessionService.class,JdbcAdminOperations.class);context.refresh();
        jdbc=context.getBean(JdbcTemplate.class);sessions=context.getBean(SessionService.class);admin=context.getBean(JdbcAdminOperations.class);
        jdbc.update("INSERT INTO portal_accounts(id,username,display_name,password_hash) VALUES('user','user','User','unused')");
    }
    @AfterEach void close(){if(context!=null)context.close();}
    SessionService.Tokens login(){return sessions.createPersistent("user",null,null);}
    void age(SessionService.Tokens token){jdbc.update("UPDATE portal_refresh_tokens SET created_at=? WHERE token_hash=?",Timestamp.from(Instant.now().minusSeconds(60)),hash(token.refresh()));}
    void denied(String access){assertThatThrownBy(()->sessions.authenticate(access)).hasMessageContaining("401");}
    void refreshDenied(String raw){assertThatThrownBy(()->sessions.renew(raw)).hasMessageContaining("401");}
    @Test void expiredAccessRenewsAfterLongIdleWithStableCsrf(){
        var original=login();age(original);
        jdbc.update("UPDATE portal_access_tokens SET expires_at=?",Timestamp.from(Instant.now().minusSeconds(1)));
        jdbc.update("UPDATE portal_sessions SET last_seen_at=?",Timestamp.from(Instant.now().minusSeconds(100L*86400)));
        denied(original.access());var renewed=sessions.renew(original.refresh());
        assertThat(renewed.access()).isNotEqualTo(original.access());assertThat(renewed.refresh()).isNotEqualTo(original.refresh());
        assertThat(renewed.csrf()).isEqualTo(original.csrf());assertThat(sessions.authenticate(renewed.access())).isEqualTo("user");
        assertThat(sessions.csrf(renewed.access())).isEqualTo(original.csrf());
    }
    @Test void concurrentRefreshAndLostResponseRetriesReturnOnePair()throws Exception{
        var original=login();age(original);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)){
            var pending=new ArrayList<Future<SessionService.Tokens>>();
            for(int i=0;i<8;i++)pending.add(pool.submit(()->{start.await();return sessions.renew(original.refresh());}));
            start.countDown();var result=pending.getFirst().get(10,TimeUnit.SECONDS);
            for(var call:pending)assertThat(call.get(10,TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(sessions.renew(original.refresh())).isEqualTo(result);
            assertThat(sessions.renew(result.refresh())).isEqualTo(result);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_refresh_tokens",Integer.class)).isEqualTo(2);
        }
    }
    @Test void staleReplayCommitsWholeFamilyRevocation(){
        var original=login();age(original);var next=sessions.renew(original.refresh());
        jdbc.update("UPDATE portal_refresh_tokens SET used_at=? WHERE token_hash=?",Timestamp.from(Instant.now().minusSeconds(31)),hash(original.refresh()));
        refreshDenied(original.refresh());denied(next.access());refreshDenied(next.refresh());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_sessions",Integer.class)).isZero();
    }
    @Test void logoutWorksWithRefreshWhenAccessHasExpiredAndBeenPruned(){
        var original=login();age(original);jdbc.update("DELETE FROM portal_access_tokens");
        var next=sessions.renew(original.refresh());sessions.logout(original.access(),next.refresh());
        denied(next.access());refreshDenied(next.refresh());
        for(String table:List.of("portal_sessions","portal_refresh_families","portal_access_tokens","portal_refresh_tokens"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
    }
    @Test void legacySessionPruningNeverEvictsPersistentDevices(){
        var logins=new ArrayList<SessionService.Tokens>();for(int i=0;i<7;i++)logins.add(login());
        jdbc.update("UPDATE portal_sessions SET last_seen_at=?",Timestamp.from(Instant.now().minusSeconds(7200)));
        String legacy="";for(int i=0;i<8;i++)legacy=sessions.create("user",null);
        for(var login:logins)assertThat(sessions.authenticate(login.access())).isEqualTo("user");
        assertThat(sessions.authenticate(legacy)).isEqualTo("user");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_sessions",Integer.class)).isEqualTo(12);
        jdbc.update("UPDATE portal_sessions SET last_seen_at=? WHERE token_hash=?",Timestamp.from(Instant.now().minusSeconds(3601)),hash(legacy));
        denied(legacy);
    }
    @Test void individualAndAllDeviceRevocationInvalidateAccessAndRefresh(){
        var first=login();var second=login();var views=admin.sessions("user");
        assertThat(views).allSatisfy(row->{assertThat(row.get("persistent")).isEqualTo(true);assertThat(row.get("expiresAt")).isNull();});
        assertThat(views.toString()).doesNotContain(first.access(),first.refresh(),hash(first.access()));
        String family=jdbc.queryForObject("SELECT session_hash FROM portal_access_tokens WHERE token_hash=?",String.class,hash(first.access()));
        // Admin references deliberately reveal neither the session anchor nor any bearer hash.
        String reference=java.util.HexFormat.of().formatHex(sha("admin-session-reference-v1:"+family));
        assertThat(admin.revokeSessions("user",reference,"owner")).isEqualTo(1);
        denied(first.access());refreshDenied(first.refresh());assertThat(sessions.authenticate(second.access())).isEqualTo("user");
        assertThat(admin.revokeSessions("user",null,"owner")).isEqualTo(1);denied(second.access());refreshDenied(second.refresh());
    }
    static String hash(String value){return java.util.HexFormat.of().formatHex(sha(value));}
    private static byte[] sha(String value){try{return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new AssertionError(e);}}
    @Test void revokeRacingRefreshCannotResurrectLogin()throws Exception{
        var original=login();age(original);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var rotating=pool.submit(()->{start.await();try{return sessions.renew(original.refresh());}catch(org.springframework.web.server.ResponseStatusException e){assertThat(e.getStatusCode().value()).isEqualTo(401);return null;}});
            var revoke=pool.submit(()->{start.await();return admin.revokeSessions("user",null,"owner");});
            start.countDown();var next=rotating.get(10,TimeUnit.SECONDS);revoke.get(10,TimeUnit.SECONDS);
            denied(original.access());refreshDenied(original.refresh());if(next!=null){denied(next.access());refreshDenied(next.refresh());}
            assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_refresh_families",Integer.class)).isZero();
        }
    }
    @Test void bearerValuesAreNotStoredAndRefreshCannotActAsAccess(){
        var token=login();denied(token.refresh());refreshDenied(token.access());refreshDenied("invalid");
        for(String table:List.of("portal_sessions","portal_refresh_families","portal_access_tokens","portal_refresh_tokens"))
            assertThat(jdbc.queryForList("SELECT * FROM "+table).toString()).doesNotContain(token.access(),token.refresh());
    }
    @Test void legacyLoginReplacesRefreshIdentityEvenWhenAccessCookieIsMissing(){
        var original=login();String legacy=sessions.createLegacy("user",null,original.refresh());
        assertThat(sessions.authenticate(legacy)).isEqualTo("user");denied(original.access());refreshDenied(original.refresh());
    }
}
