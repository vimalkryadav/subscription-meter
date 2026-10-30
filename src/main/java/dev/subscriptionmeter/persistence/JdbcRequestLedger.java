package dev.subscriptionmeter.persistence;

import dev.subscriptionmeter.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRequestLedger implements RequestLedger {
    private final JdbcTemplate jdbc;
    public JdbcRequestLedger(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void begin(String id, String memberId, String path, String model) {
        jdbc.update("INSERT INTO requests(id,member_id,path,model) VALUES(?,?,?,?)", id, memberId, path, model);
    }
    public void finish(String id, RequestResult r) {
        Usage u = r.usage();
        jdbc.update("""
            UPDATE requests SET state=?,status_code=?,duration_ms=?,upstream_request_id=?,usage_seen=?,
            input_tokens=?,output_tokens=?,cache_creation_input_tokens=?,cache_read_input_tokens=? WHERE id=?
            """, r.state(), r.status(), r.durationMs(), r.upstreamId(), u.seen(),
                u.input(), u.output(), u.cacheCreation(), u.cacheRead(), id);
    }
    public void markInterrupted() { jdbc.update("UPDATE requests SET state='interrupted' WHERE state='pending'"); }
}
