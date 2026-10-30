package dev.subscriptionmeter.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditStore implements AuditStore {
    private final JdbcTemplate jdbc;
    public JdbcAuditStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void begin(AuditContext c, String memberId, String mode, String headers, String query, String body) {
        jdbc.update("""
            INSERT INTO request_details(request_id,started_at,member_id,upstream_mode,method,path,query_string,
            peer_address,protocol,request_headers,request_body) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """, c.id(), Timestamp.from(c.startedAt()), memberId, mode, c.method(), c.path(), query,
                c.peerAddress(), c.protocol(), headers, body);
    }
    public void event(String id, Instant requestTime, int seq, long elapsed, String type, String data, boolean truncated) {
        jdbc.update("""
            INSERT INTO request_events(request_id,request_started_at,observed_at,sequence,elapsed_ms,event_type,event_data,truncated)
            VALUES(?,?,?,?,?,?,?,?)
            """, id, Timestamp.from(requestTime), Timestamp.from(Instant.now()), seq, elapsed, type, data, truncated);
    }
    public void finish(String id, Map<String,Object> f) {
        jdbc.update("""
            UPDATE request_details SET capture_state=?,response_headers=?,response_body=?,response_bytes=?,captured_bytes=?,
            event_count=?,truncated=?,upstream_headers_ms=?,first_byte_ms=?,error_type=?,error_message=?,request_bytes=?
            WHERE request_id=?
            """, f.get("capture_state"), f.get("response_headers"), f.get("response_body"), f.get("response_bytes"),
                f.get("captured_bytes"), f.get("event_count"), f.get("truncated"), f.get("upstream_headers_ms"),
                f.get("first_byte_ms"), f.get("error_type"), f.get("error_message"), f.get("request_bytes"), id);
    }
    public void access(AuditContext c, String memberId, int status, long elapsed, String headers, String query) {
        jdbc.update("""
            INSERT INTO gateway_access(request_id,started_at,member_id,method,path,status_code,duration_ms,peer_address,protocol,request_headers,query_string)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """, c.id(), Timestamp.from(c.startedAt()), memberId, c.method(), c.path(), status, elapsed,
                c.peerAddress(), c.protocol(), headers, query);
    }
    public List<Map<String,Object>> list(Instant since, String memberId, int limit, int offset) {
        return jdbc.queryForList("""
            SELECT r.*,m.name,d.capture_state,d.event_count,d.response_bytes,d.truncated,d.upstream_mode
            FROM requests r JOIN members m ON m.id=r.member_id LEFT JOIN request_details d ON d.request_id=r.id
            WHERE r.started_at>=? AND (?='' OR r.member_id=?) ORDER BY r.started_at DESC,r.id LIMIT ? OFFSET ?
            """, Timestamp.from(since), memberId, memberId, limit, offset);
    }
    public Optional<Map<String,Object>> detail(String id) {
        var rows = jdbc.queryForList("""
            SELECT r.*,m.name,d.request_id AS detail_id,d.request_headers,d.request_body,d.query_string,d.peer_address,
            d.protocol,d.method,d.upstream_mode,d.response_headers,d.response_body,d.capture_state,d.response_bytes,
            d.request_bytes,d.captured_bytes,d.event_count,d.truncated,d.upstream_headers_ms,d.first_byte_ms,d.error_type,d.error_message
            FROM requests r JOIN members m ON m.id=r.member_id LEFT JOIN request_details d ON d.request_id=r.id WHERE r.id=?
            """, id);
        return rows.stream().findFirst();
    }
    public List<Map<String,Object>> events(String id, int after, int limit) {
        return jdbc.queryForList("SELECT * FROM request_events WHERE request_id=? AND sequence>? ORDER BY sequence LIMIT ?", id, after, limit);
    }
    public List<Map<String,Object>> accesses(Instant since, int limit, int offset) {
        return jdbc.queryForList("SELECT * FROM gateway_access WHERE started_at>=? ORDER BY started_at DESC,request_id LIMIT ? OFFSET ?",
                Timestamp.from(since), limit, offset);
    }
}
