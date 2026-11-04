package dev.subscriptionmeter.accounting;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AccountingService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PriceCalculator pricing=new PriceCalculator();
    private final WorkClassifier classifier=new WorkClassifier();
    public AccountingService(JdbcTemplate jdbc) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public static Map<String,Long> components(Object raw) {
        var result=new LinkedHashMap<String,Long>();
        if(raw==null) return result;
        try {
            JsonNode node=JSON.readTree(raw.toString());
            if(node.isObject()) for(var p:node.properties()) if(p.getValue().isIntegralNumber() && p.getValue().canConvertToLong())
                result.put(p.getKey(),p.getValue().asLong());
        } catch(RuntimeException ignored) { }
        return result;
    }
    private record Price(String id,Map<String,BigDecimal> rates) {}
    private Price price(String provider,String model,Object started) {
        var rows=jdbc.queryForList("""
            SELECT id,rates_json FROM accounting_price_versions WHERE provider=? AND model=? AND currency='USD'
            AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) ORDER BY valid_from DESC,id DESC LIMIT 1
            """,provider,model,started,started);
        if(rows.isEmpty()) return new Price(null,null);
        var rates=new LinkedHashMap<String,BigDecimal>();
        try {for(var p:JSON.readTree(rows.getFirst().get("rates_json").toString()).properties()) rates.put(p.getKey(),new BigDecimal(p.getValue().asString()));}
        catch(RuntimeException invalid) {return new Price(null,null);}
        return new Price(rows.getFirst().get("id").toString(),rates);
    }
    private Map<String,Object> request(String id) {
        var rows=jdbc.queryForList("SELECT * FROM requests WHERE id=? ORDER BY started_at DESC LIMIT 1",id);
        if(rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private PriceCalculator.Estimate estimate(Map<String,Object> r) {
        var p=price((String)r.get("provider"),(String)r.get("model"),r.get("started_at"));
        return pricing.estimate(components(r.get("usage_components")),Boolean.TRUE.equals(r.get("usage_complete")),p.rates(),p.id());
    }
    public void project(String id) {
        tx.executeWithoutResult(status->{
            // Lock the request to serialize workers; manual overrides lock the assessment separately.
            var locked=jdbc.queryForList("SELECT * FROM requests WHERE id=? ORDER BY started_at DESC LIMIT 1 FOR UPDATE",id);
            if(locked.isEmpty()) return;
            var r=locked.getFirst();if("pending".equals(r.get("state")))return;
            var old=jdbc.queryForList("SELECT * FROM request_assessments WHERE request_id=?",id);
            var bodies=jdbc.queryForList("SELECT SUBSTRING(request_body,1,65536) AS body,response_headers,started_at,upstream_headers_ms,capture_state,event_count FROM request_details WHERE request_id=? ORDER BY started_at DESC LIMIT 1",id);
            Map<String,Object> capture=bodies.isEmpty()?Map.of():bodies.getFirst();
            if(bodies.isEmpty()&&!old.isEmpty()&&Objects.equals(old.getFirst().get("source_usage"),r.get("usage_components"))
                    &&Objects.equals(old.getFirst().get("source_complete"),r.get("usage_complete")))return;
            Object sourceUsage=r.get("usage_components"),sourceComplete=r.get("usage_complete");
            backfillUsage(r);
            // Complete historical usage is persisted; partial observations remain retryable when capture changes.
            if(Boolean.TRUE.equals(r.get("usage_complete"))){sourceUsage=r.get("usage_components");sourceComplete=true;}
            String fingerprint=digest(Objects.toString(r.get("usage_components"),"")+":"+r.get("usage_complete")
                +":"+capture.get("started_at")+":"+capture.get("capture_state")+":"+capture.get("event_count"));
            if(!old.isEmpty() && fingerprint.equals(old.getFirst().get("usage_digest"))){
                jdbc.update("UPDATE request_assessments SET source_usage=?,source_complete=?,source_capture_state=?,source_event_count=?,source_detail_started_at=? WHERE request_id=?",
                    sourceUsage,sourceComplete,capture.get("capture_state"),capture.get("event_count"),capture.get("started_at"),id);return;
            }
            var e=old.isEmpty()?estimate(r):estimateUsingVersion(r,(String)old.getFirst().get("price_version"));
            if(!bodies.isEmpty())new QuotaSnapshots(jdbc).recordHeaders((String)r.get("provider"),(String)r.get("upstream_account"),
                (String)capture.get("response_headers"),instant(capture.get("started_at")).plusMillis(number(capture.get("upstream_headers_ms"))));
            var c=classifier.classify((String)capture.get("body"));
            if(bodies.isEmpty()&&!old.isEmpty()){
                var prior=old.getFirst();c=new WorkClassifier.Classification((String)prior.get("category"),(String)prior.get("method"),(String)prior.get("confidence"),(String)prior.get("classifier_version"));
            }
            if(old.isEmpty())jdbc.update("""
                INSERT INTO request_assessments(request_id,usage_digest,price_version,known_value_usd,cost_status,missing_components,
                category,method,confidence,classifier_version,source_usage,observed_components,source_complete,source_capture_state,source_event_count,source_detail_started_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,fingerprint,e.priceVersion(),e.knownSubtotalUsd(),e.status(),JSON.writeValueAsString(e.missingComponents()),
                    c.category(),c.method(),c.confidence(),c.version(),sourceUsage,r.get("usage_components"),sourceComplete,capture.get("capture_state"),capture.get("event_count"),capture.get("started_at"));
            else jdbc.update("""
                UPDATE request_assessments SET assessed_at=CURRENT_TIMESTAMP,usage_digest=?,price_version=?,known_value_usd=?,
                cost_status=?,missing_components=?,category=?,method=?,confidence=?,classifier_version=?,
                source_usage=?,observed_components=?,source_complete=?,source_capture_state=?,source_event_count=?,source_detail_started_at=? WHERE request_id=?
                """,fingerprint,e.priceVersion(),e.knownSubtotalUsd(),e.status(),JSON.writeValueAsString(e.missingComponents()),
                    c.category(),c.method(),c.confidence(),c.version(),sourceUsage,r.get("usage_components"),sourceComplete,capture.get("capture_state"),capture.get("event_count"),capture.get("started_at"),id);
        });
    }
    private PriceCalculator.Estimate estimateUsingVersion(Map<String,Object> request,String version){
        if(version==null)return pricing.estimate(components(request.get("usage_components")),Boolean.TRUE.equals(request.get("usage_complete")),null,null);
        var rows=jdbc.queryForList("SELECT rates_json FROM accounting_price_versions WHERE id=?",version);
        if(rows.isEmpty())return pricing.estimate(components(request.get("usage_components")),false,null,null);
        var rates=new LinkedHashMap<String,BigDecimal>();
        try{for(var item:JSON.readTree(rows.getFirst().get("rates_json").toString()).properties())rates.put(item.getKey(),new BigDecimal(item.getValue().asString()));}
        catch(RuntimeException invalid){return pricing.estimate(components(request.get("usage_components")),false,null,null);}
        return pricing.estimate(components(request.get("usage_components")),Boolean.TRUE.equals(request.get("usage_complete")),rates,version);
    }
    private void backfillUsage(Map<String,Object> r) {
        if(r.get("usage_components")!=null || !"anthropic".equals(r.get("provider")))return;
        // Only usage/terminal records are read. Historical prompt or response text is not needed.
        var events=jdbc.queryForList("""
            SELECT event_data FROM request_events WHERE request_id=? AND event_type IN('message_start','message_delta','message_stop','error')
            ORDER BY sequence LIMIT 1000
            """,r.get("id"));
        var observer=new dev.subscriptionmeter.usage.AnthropicUsageObserver(true);
        for(var event:events) {
            byte[] bytes=("data: "+event.get("event_data")+"\n\n").getBytes(StandardCharsets.UTF_8);
            observer.accept(bytes,bytes.length);
        }
        var usage=observer.snapshot();
        if(usage.components().isEmpty())return;
        String encoded=JSON.writeValueAsString(usage.components());
        boolean complete=usage.complete() && "complete".equals(r.get("state"));
        if(complete)jdbc.update("UPDATE requests SET usage_components=?,usage_complete=? WHERE id=?",encoded,true,r.get("id"));
        r.put("usage_components",encoded);r.put("usage_complete",complete);
    }
    public int projectPending() {
        var ids=jdbc.queryForList("""
            SELECT r.id FROM requests r LEFT JOIN request_assessments a ON a.request_id=r.id
            LEFT JOIN request_details d ON d.request_id=r.id AND d.started_at=(SELECT MAX(latest.started_at) FROM request_details latest WHERE latest.request_id=r.id)
            WHERE r.state<>'pending' AND (a.request_id IS NULL OR a.source_usage IS DISTINCT FROM r.usage_components
                OR a.source_complete IS DISTINCT FROM r.usage_complete OR (d.request_id IS NOT NULL AND (a.source_capture_state IS DISTINCT FROM d.capture_state
                OR a.source_event_count IS DISTINCT FROM d.event_count OR a.source_detail_started_at IS DISTINCT FROM d.started_at)))
            ORDER BY r.started_at,r.id LIMIT 25
            """,String.class);
        for(String id:ids){
            try{project(id);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Accounting record projection delayed ({})",failure.getClass().getSimpleName());}
        }
        return ids.size();
    }
    public Map<String,Object> detail(String id) {
        var r=request(id);
        var assessments=jdbc.queryForList("SELECT * FROM request_assessments WHERE request_id=?",id);
        var out=new LinkedHashMap<String,Object>();
        out.put("usageComponents",components(r.get("usage_components")));out.put("usageComplete",r.get("usage_complete"));
        // A persisted estimate keeps its original price version; detail doesn't silently reprice history.
        if(assessments.isEmpty()) {
            out.put("estimate",estimate(r));out.put("classification",Map.of("category","unknown","method","pending","confidence","unavailable"));
        } else {
            var a=assessments.getFirst();var e=new LinkedHashMap<String,Object>();
            if(r.get("usage_components")==null){out.put("usageComponents",components(a.get("observed_components")));out.put("usageSource","retained_capture_events");}
            e.put("status",a.get("cost_status"));e.put("knownSubtotalUsd",a.get("known_value_usd"));e.put("priceVersion",a.get("price_version"));
            e.put("missingComponents",JSON.readValue(a.get("missing_components").toString(),List.class));out.put("estimate",e);
            out.put("classification",Map.of("category",Objects.toString(a.get("manual_category"),a.get("category").toString()),
                "suggestedCategory",a.get("category"),"method",a.get("manual_category")==null?a.get("method"):"admin_override",
                "confidence",a.get("manual_category")==null?a.get("confidence"):"manual","version",a.get("classifier_version")));
        }
        out.put("note","API-equivalent estimate; not a subscription bill. Unknown components are excluded from the known subtotal.");
        return out;
    }
    public void classify(String id,String category,String actor) {
        if(!WorkClassifier.CATEGORIES.contains(category)) throw new IllegalArgumentException("Unsupported category");
        project(id);
        tx.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT * FROM request_assessments WHERE request_id=? FOR UPDATE",id);
            if(rows.isEmpty()) throw new IllegalArgumentException("Classification is available after the request ends");
            jdbc.update("UPDATE request_assessments SET manual_category=? WHERE request_id=?",category,id);
            log(actor,"classification.override",id,Map.of("category",category,"previous",Objects.toString(rows.getFirst().get("manual_category"),"none")));
        });
    }
    public void log(String actor,String action,String target,Map<String,?> details) {
        jdbc.update("INSERT INTO admin_action_log(id,actor_id,action,target_id,details_json) VALUES(?,?,?,?,?)",
            UUID.randomUUID().toString(),actor,action,target,JSON.writeValueAsString(details));
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> report(Instant from,Instant to,String member,String provider) {
        String where="r.started_at>=? AND r.started_at<? AND (?='' OR r.member_id=?) AND (?='' OR r.provider=?)";
        Object[] args={Timestamp.from(from),Timestamp.from(to),member,member,provider,provider};
        var rows=jdbc.queryForList("""
            SELECT r.member_id AS "memberId",m.name,COUNT(*) AS requests,
            SUM(CASE WHEN r.usage_seen THEN 1 ELSE 0 END) AS "usageObserved",
            SUM(CASE WHEN r.usage_complete THEN 1 ELSE 0 END) AS "usageComplete",
            SUM(r.input_tokens+r.output_tokens+r.cache_creation_input_tokens+r.cache_read_input_tokens) AS "reportedTokens",
            SUM(a.known_value_usd) AS "knownValueUsd",
            SUM(CASE WHEN a.cost_status='complete' THEN 1 ELSE 0 END) AS "pricedRequests",
            SUM(CASE WHEN a.cost_status='partial' THEN 1 ELSE 0 END) AS "partiallyPricedRequests",
            SUM(CASE WHEN a.cost_status IS NULL OR a.cost_status='unpriced' THEN 1 ELSE 0 END) AS "unpricedRequests"
            FROM requests r JOIN members m ON m.id=r.member_id LEFT JOIN request_assessments a ON a.request_id=r.id WHERE
            """+where+" GROUP BY r.member_id,m.name ORDER BY \"reportedTokens\" DESC",args);
        long tokens=rows.stream().mapToLong(r->number(r.get("reportedTokens"))).sum();
        var summary=new LinkedHashMap<String,Object>();
        for(String name:List.of("requests","usageObserved","usageComplete","pricedRequests","partiallyPricedRequests","unpricedRequests"))
            summary.put(name.equals("requests")?"requestCount":name,rows.stream().mapToLong(r->number(r.get(name))).sum());
        BigDecimal total=null;
        for(var r:rows) {
            r.put("observedTokenSharePercent",tokens==0?null:BigDecimal.valueOf(number(r.get("reportedTokens"))).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(tokens),4,RoundingMode.HALF_UP));
            if(r.get("knownValueUsd")!=null) total=(total==null?BigDecimal.ZERO:total).add((BigDecimal)r.get("knownValueUsd"));
        }
        summary.put("knownValueUsd",total);
        var categories=jdbc.queryForList("""
            SELECT COALESCE(a.manual_category,a.category,'unknown') AS category,COUNT(*) AS requests,
            SUM(r.input_tokens+r.output_tokens+r.cache_creation_input_tokens+r.cache_read_input_tokens) AS "reportedTokens",
            SUM(a.known_value_usd) AS "knownValueUsd"
            FROM requests r LEFT JOIN request_assessments a ON a.request_id=r.id WHERE
            """+where+" GROUP BY COALESCE(a.manual_category,a.category,'unknown') ORDER BY requests DESC",args);
        return Map.of("from",from,"to",to,"generatedAt",Instant.now(),"summary",summary,"members",rows,"categories",categories,
            "quota",quota(provider),"notes",List.of("Member percentages are shares of recorded tokens, not shares of subscription allowance.",
                "Observed token shares use the selected report cohort as their denominator; a single-member filter can therefore show 100%.",
                "Known API-equivalent subtotals exclude unpriced components and are not actual subscription charges.",
                "Quota snapshots are the latest account observations, independent of the member filter and historical report window.",
                "Classification is a local heuristic about request content; it does not measure productivity."));
    }
    public Map<String,Object> quota(String provider) {
        var snapshots=jdbc.queryForList("""
            SELECT q.* FROM provider_quota_snapshots q WHERE (?='' OR q.provider=?) AND q.trusted=TRUE
            AND NOT EXISTS(SELECT 1 FROM provider_quota_snapshots newer WHERE newer.provider=q.provider
              AND newer.upstream_account=q.upstream_account AND newer.bucket=q.bucket AND newer.trusted=TRUE
              AND (newer.observed_at>q.observed_at OR (newer.observed_at=q.observed_at AND newer.id>q.id)))
            ORDER BY q.provider,q.upstream_account,q.bucket LIMIT 100
            """,provider,provider);
        Instant now=Instant.now();
        for(var snapshot:snapshots){
            boolean stale=instant(snapshot.get("observed_at")).isBefore(now.minusSeconds(300)) || !instant(snapshot.get("resets_at")).isAfter(now);
            snapshot.put("state",stale?"stale":"available");snapshot.put("ageSeconds",Math.max(0,Duration.between(instant(snapshot.get("observed_at")),now).getSeconds()));
        }
        boolean stale=snapshots.stream().anyMatch(q->"stale".equals(q.get("state")));
        return Map.of("state",snapshots.isEmpty()?"unavailable":stale?"stale":"available","snapshots",snapshots);
    }
    public static Instant instant(Object value) {
        if(value instanceof Timestamp t)return t.toInstant();
        if(value instanceof OffsetDateTime t)return t.toInstant();
        if(value instanceof Instant t)return t;
        throw new IllegalArgumentException("Unsupported timestamp");
    }
    public static long number(Object value){return value instanceof Number n?n.longValue():0;}
    private static String digest(String input) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
