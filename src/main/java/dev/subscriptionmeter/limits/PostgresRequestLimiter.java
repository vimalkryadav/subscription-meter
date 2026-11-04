package dev.subscriptionmeter.limits;

import dev.subscriptionmeter.domain.Member;
import dev.subscriptionmeter.deployment.InstanceLease;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Small-population serialized admission: limits remain exact across blue and green. */
@Component
public class PostgresRequestLimiter implements RequestLimiter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final InstanceLease owner;
    private final long lifetime;
    public PostgresRequestLimiter(JdbcTemplate jdbc,PlatformTransactionManager manager,InstanceLease owner,
                                  @Value("${meter.timeout-seconds:300}") int timeout) {
        this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(3);
        tx=new TransactionTemplate(manager); tx.setTimeout(3); this.owner=owner; lifetime=(timeout+60L)*1000;
    }
    public Permit acquire(Member member) {
        owner.requireHealthy();
        String id=UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT name FROM coordination_locks WHERE name='admission' FOR UPDATE",String.class);
            long now=owner.databaseNow();
            Long until=jdbc.queryForObject("SELECT lease_until FROM gateway_instances WHERE id=?",Long.class,owner.id());
            if(until==null || until<=now) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Instance lease unavailable");
            jdbc.update("DELETE FROM gateway_admissions WHERE admitted_at<? AND (closed=TRUE OR owner_instance IN (SELECT id FROM gateway_instances WHERE lease_until<?))",now-60000,now);
            int rpm=jdbc.queryForObject("SELECT COUNT(*) FROM gateway_admissions WHERE member_id=? AND admitted_at>?",Integer.class,member.id(),now-60000);
            // expires_at is a deadline hint only: a blocked live request must keep its permit.
            // Capacity is released explicitly or by fencing the entire owning process.
            String live=" FROM gateway_admissions a JOIN gateway_instances i ON a.owner_instance=i.id WHERE a.closed=FALSE AND i.lease_until>?";
            int active=jdbc.queryForObject("SELECT COUNT(*)"+live+" AND a.member_id=?",Integer.class,now,member.id());
            if(rpm>=member.rpm() || active>=member.concurrency()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Member request limit reached");
            int total=jdbc.queryForObject("SELECT COUNT(*)"+live,Integer.class,now);
            if(total>=80) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Gateway at capacity");
            jdbc.update("INSERT INTO gateway_admissions(id,member_id,owner_instance,admitted_at,expires_at) VALUES(?,?,?,?,?)",id,member.id(),owner.id(),now,now+lifetime);
        });
        AtomicBoolean closed=new AtomicBoolean();
        return () -> { if(closed.compareAndSet(false,true)) jdbc.update("UPDATE gateway_admissions SET closed=TRUE WHERE id=? AND owner_instance=?",id,owner.id()); };
    }
}
