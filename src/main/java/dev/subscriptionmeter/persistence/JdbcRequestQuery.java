package dev.subscriptionmeter.persistence;

import dev.subscriptionmeter.domain.RequestQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRequestQuery implements RequestQuery {
    private final JdbcTemplate jdbc;
    private final int retentionDays;
    public JdbcRequestQuery(JdbcTemplate jdbc, @Value("${meter.audit.retention-days:30}") int retentionDays) {
        this.jdbc=jdbc; this.retentionDays=retentionDays;
    }
    private static final String JOIN = " FROM requests r JOIN members m ON m.id=r.member_id "
            + "LEFT JOIN request_details d ON d.request_id=r.id AND d.started_at=(SELECT MAX(latest.started_at) FROM request_details latest WHERE latest.request_id=r.id) "
            + "LEFT JOIN request_assessments a ON a.request_id=r.id ";
    private static final String CAPTURE = """
        CASE WHEN d.request_id IS NULL AND r.started_at < ? THEN 'expired'
             WHEN d.request_id IS NULL THEN 'unavailable'
             WHEN d.capture_state='pending' THEN 'pending'
             WHEN d.capture_state IN ('capture_error','error','failed') THEN 'error'
             WHEN d.truncated=TRUE OR d.capture_state='truncated' THEN 'truncated'
             WHEN d.capture_state IN ('partial','disconnected','interrupted','stream_error','gateway_error') THEN 'incomplete'
             WHEN d.capture_state='upstream_error' AND (d.upstream_headers_ms IS NULL OR COALESCE(d.error_type,'')<>'') THEN 'incomplete'
             WHEN d.capture_state IN ('complete','upstream_error') THEN 'available'
             ELSE 'unavailable' END
        """;
    public List<Map<String,Object>> search(Filter f, Position before, int limit) {
        var args=new ArrayList<Object>();
        args.add(Timestamp.from(Instant.now().minus(retentionDays,ChronoUnit.DAYS)));
        StringBuilder sql=new StringBuilder("SELECT r.*,m.name,d.capture_state,d.event_count,d.response_bytes,"
                +"d.truncated,d.upstream_mode,d.first_byte_ms,COALESCE(a.manual_category,a.category,'unknown') AS work_category,"+CAPTURE+" AS capture_status"+JOIN
                +"WHERE r.started_at>=? AND r.started_at<?");
        args.add(Timestamp.from(f.from())); args.add(Timestamp.from(f.to()));
        equal(sql,args,"r.member_id",f.memberId()); equal(sql,args,"r.model",f.model());
        equal(sql,args,"r.id",f.requestId()); equal(sql,args,"r.upstream_request_id",f.upstreamRequestId());
        equal(sql,args,"r.state",f.state()); equal(sql,args,"r.status_code",f.status());
        equal(sql,args,"r.usage_seen",f.usageSeen());
        if("fable".equals(f.modelFamily()))sql.append(" AND r.provider='anthropic' AND (LOWER(r.model)='fable' OR LOWER(r.model) LIKE 'claude-fable-%')");
        equal(sql,args,"r.provider",f.provider()); equal(sql,args,"r.upstream_account",f.upstreamAccount());
        equal(sql,args,"r.client_family",f.clientFamily()); equal(sql,args,"r.member_key_prefix",f.keyPrefix());
        equal(sql,args,"COALESCE(a.manual_category,a.category,'unknown')",f.category());
        if(f.minDurationMs()!=null){sql.append(" AND r.duration_ms>=?");args.add(f.minDurationMs());}
        if(f.maxDurationMs()!=null){sql.append(" AND r.duration_ms<=?");args.add(f.maxDurationMs());}
        if(f.captureState()!=null&&!f.captureState().isBlank()){
            sql.append(" AND (").append(CAPTURE).append(")=?");
            args.add(Timestamp.from(Instant.now().minus(retentionDays,ChronoUnit.DAYS)));args.add(f.captureState());
        }
        if(before!=null){sql.append(" AND (r.started_at<? OR (r.started_at=? AND r.id<?))");
            args.add(Timestamp.from(before.startedAt()));args.add(Timestamp.from(before.startedAt()));args.add(before.id());}
        sql.append(" ORDER BY r.started_at DESC,r.id DESC LIMIT ?");args.add(limit);
        return jdbc.queryForList(sql.toString(),args.toArray());
    }
    private static void equal(StringBuilder sql,List<Object> args,String column,Object value){
        if(value!=null&&!(value instanceof String s&&s.isBlank())){sql.append(" AND ").append(column).append("=?");args.add(value);}
    }
    public Optional<Map<String,Object>> detail(String id){
        return readDetail(id,true);
    }
    public Optional<Map<String,Object>> summary(String id){
        return readDetail(id,false);
    }
    private Optional<Map<String,Object>> readDetail(String id,boolean includePayload){
        String payload=includePayload?"d.request_headers,d.request_body,d.response_headers,d.response_body,":"";
        var rows=jdbc.queryForList("SELECT "+payload+"r.*,m.name,d.request_id AS detail_id,"
                +"d.started_at AS capture_started_at,d.query_string,d.peer_address,d.protocol,d.method,d.upstream_mode,"
                +"d.capture_state,d.response_bytes,d.request_bytes,d.captured_bytes,d.event_count,d.truncated,"
                +"d.upstream_headers_ms,d.first_byte_ms,d.error_type,d.error_message,"+CAPTURE+" AS capture_status"
                +JOIN+"WHERE r.id=? ORDER BY r.started_at DESC LIMIT 1",
                Timestamp.from(Instant.now().minus(retentionDays,ChronoUnit.DAYS)),id);
        return rows.stream().findFirst();
    }
    public Optional<CaptureContent> content(String id,Instant startedAt,CaptureSection section){
        // Select only the requested section, pinned to the summary's capture version.
        String columns=switch(section){
            case INPUT -> "request_body AS body,NULL AS request_headers,NULL AS response_headers";
            case OUTPUT -> "response_body AS body,NULL AS request_headers,NULL AS response_headers";
            case HEADERS -> "NULL AS body,request_headers,response_headers";
        };
        return jdbc.query("SELECT "+columns+" FROM request_details WHERE request_id=? AND started_at=?",
                (rs,row)->new CaptureContent(startedAt,section,rs.getString("body"),
                        rs.getString("request_headers"),rs.getString("response_headers")),
                id,Timestamp.from(startedAt)).stream().findFirst();
    }
    public List<Map<String,Object>> events(String id,Instant startedAt,int after,int through,int limit){
        return jdbc.queryForList("SELECT sequence,elapsed_ms,event_type,event_data,truncated,observed_at "
                +"FROM request_events WHERE request_id=? AND request_started_at=? AND sequence>? AND sequence<=? "
                +"ORDER BY sequence LIMIT ?",id,Timestamp.from(startedAt),after,through,limit);
    }
}
