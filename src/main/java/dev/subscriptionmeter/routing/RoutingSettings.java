package dev.subscriptionmeter.routing;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Admin-controlled routing switches and model mapping; read at most every few seconds per instance. */
@Component
public class RoutingSettings {
    public record Values(boolean gptModelsEnabled, boolean fallbackEnabled, boolean codexEnabled, boolean brokerEnabled,
                         String mapOpus, String mapSonnet, String mapHaiku, BigDecimal memberWeeklyBudgetUsd,
                         String strategy, Instant updatedAt, String updatedBy) {
        public ModelMapping mapping() { return new ModelMapping(mapOpus, mapSonnet, mapHaiku); }
        public boolean balanced() { return "balanced".equals(strategy); }
    }

    private static final Duration TTL = Duration.ofSeconds(5);
    private final JdbcTemplate jdbc;
    private volatile Values cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public RoutingSettings(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        return Instant.EPOCH;
    }

    public Values get() {
        Instant now = Instant.now();
        Values v = cached;
        if (v != null && cachedAt.plus(TTL).isAfter(now)) return v;
        var r = jdbc.queryForMap("SELECT * FROM routing_settings WHERE id=1");
        v = new Values(Boolean.TRUE.equals(r.get("gpt_models_enabled")), Boolean.TRUE.equals(r.get("fallback_enabled")),
                Boolean.TRUE.equals(r.get("codex_enabled")), Boolean.TRUE.equals(r.get("broker_enabled")),
                (String) r.get("map_opus"), (String) r.get("map_sonnet"), (String) r.get("map_haiku"),
                (BigDecimal) r.get("member_weekly_budget_usd"), (String) r.get("strategy"), instant(r.get("updated_at")), (String) r.get("updated_by"));
        cached = v; cachedAt = now;
        return v;
    }

    public Values update(Values v, String actor) {
        jdbc.update("""
            UPDATE routing_settings SET gpt_models_enabled=?,fallback_enabled=?,codex_enabled=?,broker_enabled=?,map_opus=?,map_sonnet=?,map_haiku=?,
            member_weekly_budget_usd=?,strategy=?,updated_at=?,updated_by=? WHERE id=1
            """, v.gptModelsEnabled(), v.fallbackEnabled(), v.codexEnabled(), v.brokerEnabled(), v.mapOpus(), v.mapSonnet(), v.mapHaiku(),
                v.memberWeeklyBudgetUsd(), v.strategy(), Timestamp.from(Instant.now()), actor);
        cached = null;
        return get();
    }
}
