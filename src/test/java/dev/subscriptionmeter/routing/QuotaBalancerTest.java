package dev.subscriptionmeter.routing;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class QuotaBalancerTest {
    @Test void comparesTheTighterLiveWindowOfEachAccount() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:balance" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("accounting-schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var balancer = new QuotaBalancer(jdbc);
        Instant now = Instant.parse("2026-11-25T12:00:00Z");
        assertThat(balancer.headroom(now).preferred()).isEqualTo("claude"); // nothing known yet
        String insert = "INSERT INTO provider_quota_snapshots(id,provider,upstream_account,bucket,used_percentage,resets_at,observed_at,source,trusted) VALUES(?,?,?,?,?,?,?,?,TRUE)";
        jdbc.update(insert, "a", "anthropic", "primary", "five_hour", new BigDecimal("40"), Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.minusSeconds(60)), "t");
        jdbc.update(insert, "b", "anthropic", "primary", "seven_day", new BigDecimal("75"), Timestamp.from(now.plusSeconds(86400)), Timestamp.from(now.minusSeconds(60)), "t");
        // An older observation is ignored in favour of the newest one per window.
        jdbc.update(insert, "c", "anthropic", "primary", "seven_day", new BigDecimal("99"), Timestamp.from(now.plusSeconds(86400)), Timestamp.from(now.minusSeconds(3600)), "t");
        balancer.recordCodex("seven_day", 30, now.plusSeconds(5 * 86400), now.minusSeconds(30));
        var h = new QuotaBalancer(jdbc).headroom(now);
        assertThat(h.claudeUsedPercent()).isEqualTo(75.0);
        assertThat(h.codexUsedPercent()).isEqualTo(30.0);
        assertThat(h.preferred()).isEqualTo("codex");
        // After Claude's weekly window resets, its old figure no longer counts.
        var later = new QuotaBalancer(jdbc).headroom(now.plusSeconds(86401));
        assertThat(later.claudeUsedPercent()).isEqualTo(0.0);
        assertThat(later.preferred()).isEqualTo("claude");
        // Duplicate reports within the same minute are stored once; invalid ones are ignored.
        balancer.recordCodex("seven_day", 30, now.plusSeconds(5 * 86400), now.minusSeconds(30));
        balancer.recordCodex("seven_day", 130, now.plusSeconds(5 * 86400), now);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_quota_snapshots WHERE provider='openai'", Integer.class)).isEqualTo(1);
    }

    @Test void codexWindowNamesFollowTheReportedLength() {
        assertThat(CodexTarget.bucket(10080, "primary")).isEqualTo("seven_day");
        assertThat(CodexTarget.bucket(300, "primary")).isEqualTo("five_hour");
        assertThat(CodexTarget.bucket(0, "secondary")).isNull();
        assertThat(CodexTarget.bucket(null, "secondary")).isNull();
    }
}
