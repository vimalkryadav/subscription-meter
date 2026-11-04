package dev.subscriptionmeter.accounting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Versioned adapter for fractional unified-limit headers received from the authenticated upstream. */
public final class QuotaSnapshots {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    public QuotaSnapshots(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void recordHeaders(String provider,String account,String raw,Instant observed) {
        if(!"anthropic".equals(provider) || raw==null || raw.length()>65536 || observed==null || observed.isAfter(Instant.now().plusSeconds(30)))return;
        JsonNode headers;
        try{headers=JSON.readTree(raw);}catch(RuntimeException invalid){return;}
        for(var bucket:Map.of("5h","five_hour","7d","seven_day").entrySet()){
            String prefix="anthropic-ratelimit-unified-"+bucket.getKey();
            try{
                String fraction=scalar(headers.path(prefix+"-utilization")), reset=scalar(headers.path(prefix+"-reset"));
                if(fraction==null || reset==null || fraction.length()>32 || reset.length()>20)continue;
                BigDecimal used=new BigDecimal(fraction);
                if(used.signum()<0 || used.compareTo(BigDecimal.ONE)>0 || Math.abs((long)used.scale())>12)continue;
                Instant resets=Instant.ofEpochSecond(Long.parseLong(reset));
                if(resets.isBefore(observed) || resets.isAfter(observed.plusSeconds(8*86400)))continue;
                String id=UUID.nameUUIDFromBytes((provider+":"+account+":"+bucket.getValue()+":"+observed+":"+resets+":"+fraction).getBytes(StandardCharsets.UTF_8)).toString();
                save(id,provider,account,bucket.getValue(),used.multiply(BigDecimal.valueOf(100)),resets,observed,"upstream_headers_v1");
            }catch(NumberFormatException|java.time.DateTimeException invalid){/* Unknown future format stays unavailable. */}
        }
    }
    public void save(String id,String provider,String account,String bucket,BigDecimal used,Instant resets,Instant observed,String source){
        jdbc.update("""
            INSERT INTO provider_quota_snapshots(id,provider,upstream_account,bucket,used_percentage,resets_at,observed_at,source,trusted)
            VALUES(?,?,?,?,?,?,?,?,TRUE) ON CONFLICT DO NOTHING
            """,id,provider,account,bucket,used,Timestamp.from(resets),Timestamp.from(observed),source);
    }
    private String scalar(JsonNode value){
        if(value.isArray() && value.size()==1)value=value.get(0);
        return value.isString()||value.isNumber()?value.asString():null;
    }
}
