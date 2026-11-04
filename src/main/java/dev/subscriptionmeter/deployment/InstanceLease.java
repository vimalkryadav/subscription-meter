package dev.subscriptionmeter.deployment;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A boot-unique, non-resurrectable lease. An expired process must restart with a new identity. */
@Component
@DependsOnDatabaseInitialization
public class InstanceLease {
    private final JdbcTemplate jdbc;
    private final String id=UUID.randomUUID().toString();
    private final String release;
    private final AtomicBoolean fenced=new AtomicBoolean();
    private volatile long localDeadline;
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("instance-lease-").factory());
    public InstanceLease(JdbcTemplate jdbc,@Value("${meter.release:development}") String release) {
        this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(3); this.release=release;
        long started=System.nanoTime();
        long now=databaseNow();
        this.jdbc.update("INSERT INTO gateway_instances(id,release_id,lease_until) VALUES(?,?,?)",id,release,now+30000);
        localDeadline=started+25_000_000_000L;
    }
    public long databaseNow() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class).getTime(); }
    public String id() { return id; }
    public String release() { return release; }
    public boolean healthy() { return !fenced.get() && System.nanoTime()<localDeadline; }
    public void requireHealthy() {
        if(!healthy()) { fenced.set(true); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Instance lease unavailable"); }
    }
    @PostConstruct void startHeartbeat() { heartbeats.scheduleWithFixedDelay(this::heartbeat,5,5,TimeUnit.SECONDS); }
    @PreDestroy void stopHeartbeat() { fenced.set(true); heartbeats.shutdownNow(); }
    public void heartbeat() {
        if(!healthy()) { fenced.set(true); return; }
        try {
            long started=System.nanoTime();
            long now=databaseNow();
            if(jdbc.update("UPDATE gateway_instances SET lease_until=? WHERE id=? AND lease_until>?",now+30000,id,now)!=1) {
                fenced.set(true); return;
            }
            localDeadline=started+25_000_000_000L;
        } catch(RuntimeException unavailable) {
            // Keep the last successfully renewed deadline; failures never extend the lease.
        }
    }
    @Scheduled(fixedDelay=10000)
    public void recoverAbandoned() {
        if(!healthy()) return;
        try {
            long now=databaseNow();
            // NULL owners belong to the legacy deployment and are never recovered automatically.
            jdbc.update("UPDATE requests SET state='interrupted' WHERE state='pending' AND owner_instance IN (SELECT id FROM gateway_instances WHERE lease_until<?)",now);
            jdbc.update("UPDATE request_details SET capture_state='interrupted' WHERE capture_state='pending' AND EXISTS (SELECT 1 FROM requests r WHERE r.id=request_details.request_id AND r.state='interrupted' AND r.owner_instance IS NOT NULL)");
        } catch(RuntimeException unavailable) {
            // Recovery is retried separately; it never delays the dedicated heartbeat executor.
        }
    }

}
