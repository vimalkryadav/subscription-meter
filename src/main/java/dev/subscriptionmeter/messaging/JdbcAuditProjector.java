package dev.subscriptionmeter.messaging;

import dev.subscriptionmeter.audit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Kafka offsets may be committed only after accept returns. Parts and projections commit atomically. */
public final class JdbcAuditProjector {
    private final JdbcTemplate jdbc;
    private final JdbcAuditStore store;
    private final TransactionTemplate transactions;
    private final AuditCodec codec=new AuditCodec();
    private final int captureDays,metadataDays;
    public JdbcAuditProjector(JdbcTemplate jdbc,TransactionTemplate transactions){this(jdbc,transactions,30,90);}
    public JdbcAuditProjector(JdbcTemplate jdbc,TransactionTemplate transactions,int captureDays,int metadataDays){
        if(captureDays<1||metadataDays<captureDays||metadataDays>3650)throw new IllegalArgumentException("Invalid audit retention");
        this.jdbc=jdbc;this.store=new JdbcAuditStore(jdbc);this.transactions=transactions;this.captureDays=captureDays;this.metadataDays=metadataDays;
    }
    public void accept(AuditEnvelope e){
        acceptBatch(List.of(e));
    }
    public void acceptBatch(List<AuditEnvelope> batch){
        if(batch.size()>64)throw new AuditUnavailableException("Projection batch exceeds bound");
        for(var e:batch)codec.decode(codec.encode(e));
        transactions.executeWithoutResult(status->{for(var e:batch)projectPart(e);});
    }
    private void projectPart(AuditEnvelope e){
        jdbc.update("""
            INSERT INTO audit_operations(operation_id,request_id,kind,observed_at,part_count,payload_bytes,sha256)
            VALUES(?,?,?,?,?,?,?) ON CONFLICT(operation_id) DO NOTHING
            """,e.operationId(),e.requestId(),e.kind(),Timestamp.from(Instant.parse(e.observedAt())),e.parts(),e.payloadBytes(),e.sha256());
        var op=jdbc.queryForMap("SELECT * FROM audit_operations WHERE operation_id=? FOR UPDATE",e.operationId());
        if(!e.sha256().equals(op.get("sha256")) || !e.requestId().equals(op.get("request_id")) || !e.kind().equals(op.get("kind"))
                || ((Number)op.get("part_count")).intValue()!=e.parts() || ((Number)op.get("payload_bytes")).intValue()!=e.payloadBytes())
            throw new AuditUnavailableException("Conflicting audit operation identity");
        if(Boolean.TRUE.equals(op.get("completed")))return;
        if(Instant.parse(e.observedAt()).isBefore(Instant.now().minus(java.time.Duration.ofDays(e.kind().equals("RESULT")?metadataDays:captureDays)))){
            expire(e.operationId());return;
        }
        byte[] decoded=Base64.getDecoder().decode(e.data());
        int expected=Math.min(AuditCodec.PART_BYTES,e.payloadBytes()-e.part()*AuditCodec.PART_BYTES);
        if(decoded.length!=expected)throw new AuditUnavailableException("Invalid audit part size");
        jdbc.update("INSERT INTO audit_payload_parts(operation_id,part_number,data) VALUES(?,?,?) ON CONFLICT(operation_id,part_number) DO NOTHING",e.operationId(),e.part(),e.data());
        String existing=jdbc.queryForObject("SELECT data FROM audit_payload_parts WHERE operation_id=? AND part_number=?",String.class,e.operationId(),e.part());
        if(!e.data().equals(existing))throw new AuditUnavailableException("Conflicting audit part identity");
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM audit_payload_parts WHERE operation_id=?",Integer.class,e.operationId());
        if(count!=e.parts())return;
        var bytes=new ByteArrayOutputStream(e.payloadBytes());
        jdbc.query("SELECT data FROM audit_payload_parts WHERE operation_id=? ORDER BY part_number",rs->{bytes.writeBytes(Base64.getDecoder().decode(rs.getString(1)));},e.operationId());
        byte[] payload=bytes.toByteArray();
        if(payload.length!=e.payloadBytes() || !AuditCodec.digest(payload).equals(e.sha256()))throw new AuditUnavailableException("Audit checksum mismatch");
        apply(e,codec.fields(payload));
        jdbc.update("UPDATE audit_operations SET completed=TRUE,completed_at=CURRENT_TIMESTAMP WHERE operation_id=?",e.operationId());
        jdbc.update("DELETE FROM audit_payload_parts WHERE operation_id=?",e.operationId());
    }
    private AuditContext context(String id,Map<String,Object> m){
        return new AuditContext(id,Instant.parse((String)m.get("started_at")),(String)m.get("method"),(String)m.get("path"),null,
                Map.of(),(String)m.get("peer_address"),(String)m.get("protocol"));
    }
    private void apply(AuditEnvelope e,Map<String,Object> m){
        switch(e.kind()){
            case "BEGIN" -> store.begin(context(e.requestId(),m),(String)m.get("member_id"),(String)m.get("mode"),(String)m.get("headers"),(String)m.get("query"),(String)m.get("body"));
            case "HEADERS" -> {
                int updated=jdbc.update("UPDATE request_details SET response_headers=?,upstream_headers_ms=? WHERE request_id=?",m.get("response_headers"),m.get("upstream_headers_ms"),e.requestId());
                if(updated!=1)throw new AuditUnavailableException("Audit begin must project before headers");
            }
            case "EVENT" -> jdbc.update("""
                INSERT INTO request_events(request_id,request_started_at,observed_at,sequence,elapsed_ms,event_type,event_data,truncated)
                VALUES(?,?,?,?,?,?,?,?)
                """,e.requestId(),Timestamp.from(Instant.parse((String)m.get("started_at"))),Timestamp.from(Instant.parse(e.observedAt())),
                    m.get("sequence"),m.get("elapsed_ms"),m.get("type"),m.get("data"),m.get("truncated"));
            case "FINISH" -> {
                Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM request_details WHERE request_id=?",Integer.class,e.requestId());
                if(count==null || count!=1)throw new AuditUnavailableException("Audit begin must project before finish");
                if("complete".equals(m.get("capture_state"))){
                    var summary=jdbc.queryForMap("SELECT COUNT(*) AS total,COUNT(DISTINCT sequence) AS unique_sequences,COALESCE(MIN(sequence),0) AS first_sequence,COALESCE(MAX(sequence),0) AS last_sequence FROM request_events WHERE request_id=?",e.requestId());
                    long expected=((Number)m.get("event_count")).longValue();
                    if(((Number)summary.get("total")).longValue()!=expected || ((Number)summary.get("unique_sequences")).longValue()!=expected
                            || ((Number)summary.get("first_sequence")).longValue()!=(expected==0?0:1)||((Number)summary.get("last_sequence")).longValue()!=expected)
                        throw new AuditUnavailableException("Complete audit manifest has missing or duplicate events");
                }
                store.finish(e.requestId(),m);
            }
            case "ACCESS" -> store.access(context(e.requestId(),m),(String)m.get("member_id"),((Number)m.get("status")).intValue(),
                    ((Number)m.get("elapsed_ms")).longValue(),(String)m.get("headers"),(String)m.get("query"));
            case "RESULT" -> {
                var components=new HashMap<String,Long>();
                if(m.get("components") instanceof Map<?,?> values)
                    values.forEach((key,value)->components.put((String)key,((Number)value).longValue()));
                var usage=new dev.subscriptionmeter.domain.Usage(((Number)m.get("input")).longValue(),((Number)m.get("output")).longValue(),
                        ((Number)m.get("cache_creation")).longValue(),((Number)m.get("cache_read")).longValue(),Boolean.TRUE.equals(m.get("seen")),components,Boolean.TRUE.equals(m.get("complete")));
                Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM requests WHERE id=?",Integer.class,e.requestId());
                if(count==null||count!=1)throw new AuditUnavailableException("Request admission must exist before result projection");
                new dev.subscriptionmeter.persistence.JdbcRequestLedger(jdbc).finish(e.requestId(),new dev.subscriptionmeter.domain.RequestResult(
                        (String)m.get("state"),((Number)m.get("status")).intValue(),((Number)m.get("duration_ms")).longValue(),(String)m.get("upstream_id"),usage));
            }
            default -> throw new AuditUnavailableException("Unsupported audit operation kind");
        }
    }
    private void expire(String id){
        jdbc.update("UPDATE audit_operations SET completed=TRUE,completed_at=CURRENT_TIMESTAMP,discard_reason='retention_expired' WHERE operation_id=?",id);
        jdbc.update("DELETE FROM audit_payload_parts WHERE operation_id=?",id);
    }
    /** Bounded cleanup keeps tombstones so delayed parts cannot resurrect expired request bodies. */
    public void expireIncomplete(){
        transactions.executeWithoutResult(status->{
            var ids=jdbc.queryForList("""
                SELECT operation_id FROM audit_operations WHERE completed=FALSE
                AND ((kind='RESULT' AND observed_at<?) OR (kind<>'RESULT' AND observed_at<?))
                ORDER BY observed_at LIMIT 100 FOR UPDATE SKIP LOCKED
                """,String.class,Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(metadataDays))),
                    Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(captureDays))));
            for(String id:ids)expire(id);
        });
    }
}
