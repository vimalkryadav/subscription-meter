package dev.subscriptionmeter.accounting;

import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Keep database-specific latest-row lookup separate from accounting/classification rules. */
public final class PendingAssessmentQuery {
    private final JdbcTemplate jdbc;
    private final String sql;
    public PendingAssessmentQuery(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        String database=jdbc.execute((ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
        String latest=switch(database){
            case "PostgreSQL" -> """
                LEFT JOIN LATERAL (SELECT request_id,started_at,capture_state,event_count
                    FROM request_details latest WHERE latest.request_id=r.id
                    ORDER BY started_at DESC LIMIT 1) d ON TRUE
                """;
            // H2 is the in-memory test/development backend and has no lateral join support.
            case "H2" -> """
                LEFT JOIN request_details d ON d.request_id=r.id AND d.started_at=(
                    SELECT MAX(latest.started_at) FROM request_details latest WHERE latest.request_id=r.id)
                """;
            default -> throw new IllegalArgumentException("Unsupported accounting database");
        };
        sql="SELECT r.id FROM requests r LEFT JOIN request_assessments a ON a.request_id=r.id\n"+latest+"""
            WHERE r.state<>'pending' AND (a.request_id IS NULL OR a.source_usage IS DISTINCT FROM r.usage_components
                OR a.source_complete IS DISTINCT FROM r.usage_complete OR (d.request_id IS NOT NULL AND (a.classifier_version IS DISTINCT FROM ? OR a.source_capture_state IS DISTINCT FROM d.capture_state
                OR a.source_event_count IS DISTINCT FROM d.event_count OR a.source_detail_started_at IS DISTINCT FROM d.started_at)))
            ORDER BY r.started_at,r.id LIMIT 25
            """;
    }
    public List<String> nextIds() { return jdbc.queryForList(sql,String.class,WorkClassifier.VERSION); }
}
