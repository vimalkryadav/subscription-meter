package dev.subscriptionmeter;

import dev.subscriptionmeter.deployment.*;
import dev.subscriptionmeter.domain.Member;
import dev.subscriptionmeter.limits.PostgresRequestLimiter;
import dev.subscriptionmeter.portal.AuthThrottle;
import dev.subscriptionmeter.persistence.JdbcRequestLedger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class CoordinationTest {
    record Fixture(JdbcTemplate jdbc,DataSourceTransactionManager tx) {}
    Fixture fixture() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new Fixture(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));
    }
    @Test void twoInstancesShareConcurrencyAndRateAndReleaseOnce() {
        var f=fixture();
        var blue=new PostgresRequestLimiter(f.jdbc,f.tx,new InstanceLease(f.jdbc,"blue"),300);
        var green=new PostgresRequestLimiter(f.jdbc,f.tx,new InstanceLease(f.jdbc,"green"),300);
        var member=new Member("a","a","",true,2,1);
        var first=blue.acquire(member);
        assertThatThrownBy(()->green.acquire(member)).hasMessageContaining("429");
        first.close(); first.close();
        green.acquire(member).close();
        assertThatThrownBy(()->blue.acquire(member)).hasMessageContaining("429");
    }
    @Test void simultaneousAdmissionHasOneWinner() throws Exception {
        var f=fixture();
        var blue=new PostgresRequestLimiter(f.jdbc,f.tx,new InstanceLease(f.jdbc,"blue"),300);
        var green=new PostgresRequestLimiter(f.jdbc,f.tx,new InstanceLease(f.jdbc,"green"),300);
        var member=new Member("race","race","",true,60,1);
        var start=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> first=()-> { start.await(); try { blue.acquire(member); return true; } catch(org.springframework.web.server.ResponseStatusException e) { return false; } };
            java.util.concurrent.Callable<Boolean> second=()-> { start.await(); try { green.acquire(member); return true; } catch(org.springframework.web.server.ResponseStatusException e) { return false; } };
            var a=pool.submit(first); var b=pool.submit(second);
            assertThat(java.util.List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void authBudgetSharedAndNormalized() throws Exception {
        var f=fixture(); var blue=new AuthThrottle(f.jdbc,f.tx); var green=new AuthThrottle(f.jdbc,f.tx);
        for(int i=0;i<10;i++) (i%2==0?blue:green).acquire(i%2==0?"Alice":" ALICE ").close();
        assertThatThrownBy(()->green.acquire("alice")).hasMessageContaining("429");
        assertThat(f.jdbc.queryForList("SELECT subject_hash FROM auth_attempts",String.class)).allMatch(s->s.length()==64&&!s.contains("alice"));
    }
    @Test void recoveryNeverInterruptsLiveOrLegacyRequests() {
        var f=fixture(); var blue=new InstanceLease(f.jdbc,"blue"); var green=new InstanceLease(f.jdbc,"green");
        f.jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES('a','a','hash','prefix',60,4)");
        new JdbcRequestLedger(f.jdbc,blue).begin("live","a","/v1/messages","m");
        new JdbcRequestLedger(f.jdbc).begin("legacy","a","/v1/messages","m");
        green.heartbeat(); green.recoverAbandoned();
        assertThat(f.jdbc.queryForObject("SELECT state FROM requests WHERE id='live'",String.class)).isEqualTo("pending");
        f.jdbc.update("UPDATE gateway_instances SET lease_until=0 WHERE id=?",blue.id());
        green.heartbeat(); green.recoverAbandoned();
        assertThat(f.jdbc.queryForObject("SELECT state FROM requests WHERE id='live'",String.class)).isEqualTo("interrupted");
        assertThat(f.jdbc.queryForObject("SELECT state FROM requests WHERE id='legacy'",String.class)).isEqualTo("pending");
        blue.heartbeat(); assertThat(blue.healthy()).isFalse();
    }
    @Test void drainClosesAdmissionWithoutLosingExistingWork() {
        var state=new DeploymentState(); var permit=state.enter(); state.drain();
        assertThatThrownBy(state::enter).hasMessageContaining("503");
        assertThat(state.active()).isEqualTo(1); permit.close(); permit.close(); assertThat(state.active()).isZero();
        state.resume(); state.enter().close(); assertThat(state.active()).isZero();
    }
}
