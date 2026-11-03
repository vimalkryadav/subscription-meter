package dev.subscriptionmeter.persistence;

import dev.subscriptionmeter.domain.MetricsReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMetricsReader implements MetricsReader {
    private final JdbcTemplate jdbc;
    public JdbcMetricsReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Details have shorter retention than the ledger: keep metadata-only rows in the all-mode view.
    private static final String WINDOW = """
        WITH windowed AS (
          SELECT r.*,m.name,d.first_byte_ms,d.truncated,d.capture_state,
            r.input_tokens+r.output_tokens+r.cache_creation_input_tokens+r.cache_read_input_tokens AS tokens,
            FLOOR(EXTRACT(EPOCH FROM r.started_at)/CAST(? AS INTEGER))*CAST(? AS INTEGER) AS bucket
          FROM requests r JOIN members m ON m.id=r.member_id
          LEFT JOIN request_details d ON d.request_id=r.id
          WHERE r.started_at>=? AND r.started_at<? AND (?='all' OR d.upstream_mode=?)
        )
        """;
    private static final String COUNTS = """
        COUNT(*) AS requests,COALESCE(SUM(tokens),0) AS tokens,
        COALESCE(SUM(CASE WHEN state='complete' THEN 1 ELSE 0 END),0) AS completed,
        COALESCE(SUM(CASE WHEN status_code>=400 OR state NOT IN ('pending','complete') THEN 1 ELSE 0 END),0) AS failed,
        COALESCE(SUM(CASE WHEN state='pending' THEN 1 ELSE 0 END),0) AS pending
        """;
    private static String percentiles(String column, String prefix) {
        return "PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY "+column+") AS "+prefix+"p50,"
             + "PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY "+column+") AS "+prefix+"p95,"
             + "PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY "+column+") AS "+prefix+"p99";
    }

    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report read(Instant from, Instant to, int stepSeconds, String mode) {
        Object[] args = {stepSeconds,stepSeconds,Timestamp.from(from),Timestamp.from(to),mode,mode};
        Map<String,Object> summary = jdbc.queryForMap(WINDOW+"SELECT "+COUNTS+","+
            percentiles("duration_ms","duration_")+"""
            ,COALESCE(SUM(input_tokens),0) AS input_tokens,COALESCE(SUM(output_tokens),0) AS output_tokens,
            COALESCE(SUM(cache_creation_input_tokens),0) AS cache_write_tokens,
            COALESCE(SUM(cache_read_input_tokens),0) AS cache_read_tokens,
            COALESCE(SUM(CASE WHEN usage_seen THEN 1 ELSE 0 END),0) AS usage_observed,
            COALESCE(SUM(CASE WHEN truncated THEN 1 ELSE 0 END),0) AS truncated,
            COALESCE(SUM(CASE WHEN capture_state IS NULL THEN 1 ELSE 0 END),0) AS metadata_only
            FROM windowed
            """,args);
        List<Map<String,Object>> traffic = jdbc.queryForList(WINDOW+"SELECT bucket,"+COUNTS+
            " FROM windowed GROUP BY bucket ORDER BY bucket",args);
        // Percentiles are computed on request samples in the DB, never averaged across buckets.
        List<Map<String,Object>> timings = jdbc.queryForList(WINDOW+"SELECT bucket,model,"+
            percentiles("duration_ms","duration_")+","+percentiles("first_byte_ms","first_byte_")+
            " FROM windowed GROUP BY bucket,model ORDER BY bucket,model",args);
        List<Map<String,Object>> errors = jdbc.queryForList(WINDOW+"""
            SELECT COALESCE(CAST(status_code AS VARCHAR),'No status') AS code,state,COUNT(*) AS requests
            FROM windowed WHERE status_code>=400 OR state NOT IN ('pending','complete')
            GROUP BY status_code,state ORDER BY requests DESC,code,state
            """,args);
        List<Map<String,Object>> members = jdbc.queryForList(WINDOW+"""
            SELECT member_id AS id,name AS label,COUNT(*) AS requests,SUM(tokens) AS tokens
            FROM windowed GROUP BY member_id,name ORDER BY requests DESC,name,member_id LIMIT 10
            """,args);
        List<Map<String,Object>> models = jdbc.queryForList(WINDOW+"""
            SELECT model AS label,COUNT(*) AS requests,SUM(tokens) AS tokens
            FROM windowed GROUP BY model ORDER BY requests DESC,model LIMIT 10
            """,args);
        return new Report(from,to,stepSeconds,mode,summary,traffic,timings,errors,members,models);
    }
}
