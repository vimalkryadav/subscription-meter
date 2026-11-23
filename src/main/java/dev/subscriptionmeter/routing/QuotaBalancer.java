package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.accounting.QuotaSnapshots;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Remaining quota of the Claude subscription and the Codex login, from the latest trusted observations
 * (shared by all app instances). Balanced routing sends each new session to whichever has more left.
 */
@Component
public class QuotaBalancer {
    public record Headroom(Double claudeUsedPercent, Double codexUsedPercent) {
        /** Used percentage of the tighter window; unknown counts as unused. */
        double claude() { return claudeUsedPercent == null ? 0 : claudeUsedPercent; }
        double codex() { return codexUsedPercent == null ? 0 : codexUsedPercent; }
        /** Codex only when it has strictly more headroom; ties stay on Claude. */
        public String preferred() { return codex() < claude() ? "codex" : "claude"; }
    }

    private static final java.time.Duration CACHE = java.time.Duration.ofSeconds(15);
    private final JdbcTemplate jdbc;
    private final QuotaSnapshots snapshots;
    private volatile Headroom cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public QuotaBalancer(JdbcTemplate jdbc) { this.jdbc = jdbc; this.snapshots = new QuotaSnapshots(jdbc); }

    /** Highest used percentage among the account's live windows (a window past its reset counts as fresh). */
    Double used(String provider, String account, Collection<String> buckets, Instant now) {
        Double max = null;
        for (String bucket : buckets) {
            var rows = jdbc.queryForList("""
                SELECT used_percentage, resets_at FROM provider_quota_snapshots
                WHERE provider=? AND upstream_account=? AND bucket=? AND trusted=TRUE ORDER BY observed_at DESC LIMIT 1
                """, provider, account, bucket);
            if (rows.isEmpty()) continue;
            Object reset = rows.getFirst().get("resets_at");
            Instant resets = reset instanceof Timestamp t ? t.toInstant() : reset instanceof OffsetDateTime o ? o.toInstant() : Instant.EPOCH;
            double value = resets.isAfter(now) ? ((BigDecimal) rows.getFirst().get("used_percentage")).doubleValue() : 0;
            max = max == null ? value : Math.max(max, value);
        }
        return max;
    }

    private final Map<String, Double> perDeployment = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Instant> perDeploymentAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** Used percentage of a deployment's tightest live window (null when unknown). */
    public Double usedPercent(Deployment d, Instant now) {
        if (d.broker()) return null; // budget limits are enforced by the broker itself
        Instant at = perDeploymentAt.get(d.id());
        if (at != null && at.plus(CACHE).isAfter(now)) return perDeployment.get(d.id());
        Double used = d.anthropic() ? used("anthropic", d.account(), List.of("five_hour", "seven_day"), now)
                : used("openai", d.account(), List.of("seven_day", "five_hour", "primary", "secondary"), now);
        if (used == null) perDeployment.remove(d.id()); else perDeployment.put(d.id(), used);
        perDeploymentAt.put(d.id(), now);
        return used;
    }

    public Headroom headroom(Instant now) {
        Headroom h = cached;
        if (h != null && cachedAt.plus(CACHE).isAfter(now)) return h;
        h = new Headroom(used("anthropic", "primary", List.of("five_hour", "seven_day"), now),
                used("openai", "codex", List.of("seven_day", "secondary"), now));
        cached = h; cachedAt = now;
        return h;
    }

    /** Stores Codex's own quota report (rounded to the minute, so at most one row per window per minute). */
    public void recordCodex(String bucket, Integer usedPercent, Instant resetsAt, Instant now) { recordCodex("codex", bucket, usedPercent, resetsAt, now); }

    public void recordCodex(String account, String bucket, Integer usedPercent, Instant resetsAt, Instant now) {
        if (usedPercent == null || resetsAt == null || usedPercent < 0 || usedPercent > 100 || !resetsAt.isAfter(now)) return;
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        String id = UUID.nameUUIDFromBytes(("openai:" + account + ":" + bucket + ":" + minute + ":" + resetsAt + ":" + usedPercent).getBytes(StandardCharsets.UTF_8)).toString();
        try { snapshots.save(id, "openai", account, bucket, BigDecimal.valueOf(usedPercent), resetsAt, minute, "codex_headers_v1"); }
        catch (RuntimeException ignored) { /* quota bookkeeping never fails a member request */ }
        cached = null;
    }
}
