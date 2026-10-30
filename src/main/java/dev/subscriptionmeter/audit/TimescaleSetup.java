package dev.subscriptionmeter.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TimescaleSetup implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int retentionDays;
    private final int metadataDays;
    public TimescaleSetup(JdbcTemplate jdbc,@Value("${meter.audit.timescale-enabled:false}") boolean enabled,
                          @Value("${meter.audit.retention-days:30}") int days,
                          @Value("${meter.audit.metadata-retention-days:90}") int metadataDays) {
        if (days<1 || days>3650) throw new IllegalArgumentException("Audit retention must be 1..3650 days");
        if (metadataDays<days || metadataDays>3650) throw new IllegalArgumentException("Metadata retention must cover detail retention");
        this.jdbc=jdbc; this.enabled=enabled; this.retentionDays=days; this.metadataDays=metadataDays;
    }
    public void run(ApplicationArguments args) {
        jdbc.update("UPDATE request_details SET capture_state='interrupted' WHERE capture_state='pending'");
        if (!enabled) return;
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
        // Hypertable unique constraints include the time partition key. No table references requests.id.
        jdbc.execute("""
            DO $$ BEGIN
              IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='requests'::regclass AND contype='p'
                         AND pg_get_constraintdef(oid)='PRIMARY KEY (id)') THEN
                ALTER TABLE requests DROP CONSTRAINT requests_pkey;
                ALTER TABLE requests ADD PRIMARY KEY(id,started_at);
              END IF;
            END $$
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS requests_id_lookup ON requests(id)");
        for (String table : new String[]{"requests","request_details","request_events","gateway_access"}) {
            String time=table.equals("request_events")?"observed_at":"started_at";
            jdbc.execute("SELECT create_hypertable('"+table+"',by_range('"+time+"',INTERVAL '1 day'),if_not_exists=>TRUE,migrate_data=>TRUE)");
            jdbc.execute("SELECT remove_retention_policy('"+table+"',if_exists=>TRUE)");
            int days=table.equals("requests")?metadataDays:retentionDays;
            jdbc.execute("SELECT add_retention_policy('"+table+"',INTERVAL '"+days+" days',if_not_exists=>TRUE)");
        }
    }
}
