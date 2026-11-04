package dev.subscriptionmeter.persistence;

import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.deployment.InstanceLease;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRequestLedger implements RequestLedger {
    private final JdbcTemplate jdbc;
    private final InstanceLease owner;
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    public JdbcRequestLedger(JdbcTemplate jdbc) { this.jdbc = jdbc; this.owner=null; }
    @Autowired public JdbcRequestLedger(JdbcTemplate jdbc,InstanceLease owner) { this.jdbc=jdbc; this.owner=owner; }
    public void begin(String id,String memberId,String path,String model) {
        begin(id,memberId,path,model,java.time.Instant.now());
    }
    public void begin(String id,String memberId,String path,String model,java.time.Instant startedAt) {
        if(owner!=null) owner.requireHealthy();
        jdbc.update("INSERT INTO requests(id,member_id,path,model,owner_instance,started_at,member_key_prefix) VALUES(?,?,?,?,?,?,(SELECT key_prefix FROM members WHERE id=?))", id,memberId,path,model,owner==null?null:owner.id(),java.sql.Timestamp.from(startedAt),memberId);
    }
    public void finish(String id, RequestResult r) {
        if(owner!=null) owner.requireHealthy();
        Usage u = r.usage();
        jdbc.update("""
            UPDATE requests SET state=?,status_code=?,duration_ms=?,upstream_request_id=?,usage_seen=?,
            input_tokens=?,output_tokens=?,cache_creation_input_tokens=?,cache_read_input_tokens=?,
            usage_components=?,usage_complete=? WHERE id=?
            """, r.state(), r.status(), r.durationMs(), r.upstreamId(), u.seen(),
                u.input(), u.output(), u.cacheCreation(), u.cacheRead(), JSON.writeValueAsString(u.components()),u.complete(),id);
    }
    public void identify(String id,String provider,String account,String client) {
        jdbc.update("UPDATE requests SET provider=?,upstream_account=?,client_family=? WHERE id=?",provider,account,client,id);
    }
    public void markInterrupted() {
        // Recovery is fenced by per-instance leases, never a global boot-time update.
        if(owner!=null) owner.heartbeat();
    }
}
