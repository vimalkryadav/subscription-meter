package dev.subscriptionmeter.persistence;

import dev.subscriptionmeter.domain.AnalyticsReader;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnalyticsReader implements AnalyticsReader {
    private final JdbcTemplate jdbc;
    public JdbcAnalyticsReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String, Object>> summarize(Instant since) {
        return jdbc.queryForList("""
            SELECT m.id AS member_id,m.name,r.model,r.state,COUNT(*) AS requests,
            SUM(r.input_tokens) AS input_tokens,SUM(r.output_tokens) AS output_tokens,
            SUM(r.cache_creation_input_tokens) AS cache_creation_input_tokens,
            SUM(r.cache_read_input_tokens) AS cache_read_input_tokens,
            MAX(r.started_at) AS last_activity
            FROM requests r JOIN members m ON m.id=r.member_id WHERE r.started_at>=?
            GROUP BY m.id,m.name,r.model,r.state ORDER BY m.name,r.model,r.state
            """, Timestamp.from(since));
    }
}
