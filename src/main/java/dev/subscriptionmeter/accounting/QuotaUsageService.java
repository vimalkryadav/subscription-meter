package dev.subscriptionmeter.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Account observations and gateway activity have different clocks and denominators. */
@Service
public class QuotaUsageService {
    private final JdbcTemplate jdbc;
    public QuotaUsageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Member(String memberId, String name, long requests, long usageObserved,
                         long usageComplete, BigDecimal reportedTokens, BigDecimal gatewayTokenSharePercent,
                         BigDecimal allowanceUsedPercent) {}
    public record Window(String provider, String account, String bucket, BigDecimal usedPercent,
                         Instant observedAt, Instant resetsAt, String source, String freshness,
                         Instant startsAt, Instant gatewayThrough, long requests, long usageObserved,
                         long usageComplete, BigDecimal reportedTokens, List<Member> members) {}
    public record Report(Instant generatedAt, String outsideUsage, List<Window> windows) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report(Instant now, String provider) {
        var snapshots = jdbc.queryForList("""
            SELECT q.* FROM provider_quota_snapshots q
            WHERE q.trusted=TRUE AND q.provider='anthropic' AND (?='' OR q.provider=?)
              AND q.bucket IN ('five_hour','seven_day') AND q.observed_at<=?
              AND NOT EXISTS (SELECT 1 FROM provider_quota_snapshots newer
                WHERE newer.trusted=TRUE AND newer.provider=q.provider
                  AND newer.upstream_account=q.upstream_account AND newer.bucket=q.bucket
                  AND newer.observed_at<=?
                  AND (newer.observed_at>q.observed_at OR (newer.observed_at=q.observed_at AND newer.id>q.id)))
            ORDER BY q.upstream_account,q.bucket LIMIT 100
            """, provider, provider, Timestamp.from(now), Timestamp.from(now));
        var windows = snapshots.stream().map(q -> {
            String bucket = q.get("bucket").toString();
            Instant observed = AccountingService.instant(q.get("observed_at"));
            Instant reset = AccountingService.instant(q.get("resets_at"));
            Instant start = reset.minus(Duration.ofHours(bucket.equals("five_hour") ? 5 : 168));
            Instant through = now.isBefore(reset) ? now : reset;
            String freshness = !reset.isAfter(now) ? "reset_passed" : observed.isBefore(now.minusSeconds(300)) ? "stale" : "recent";
            // A reset outside the supported window cannot establish a useful reporting interval.
            boolean valid = !observed.isBefore(start) && observed.isBefore(reset);
            List<Member> members = valid ? members(q.get("provider").toString(), q.get("upstream_account").toString(), start, through) : List.of();
            BigDecimal tokens = members.stream().map(Member::reportedTokens).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Window(q.get("provider").toString(), q.get("upstream_account").toString(), bucket,
                new BigDecimal(q.get("used_percentage").toString()), observed, reset, q.get("source").toString(),
                valid ? freshness : "invalid_window", valid ? start : null, valid ? through : null,
                members.stream().mapToLong(Member::requests).sum(), members.stream().mapToLong(Member::usageObserved).sum(),
                members.stream().mapToLong(Member::usageComplete).sum(), tokens, members);
        }).toList();
        return new Report(now, "unknown", windows);
    }

    private List<Member> members(String provider, String account, Instant from, Instant to) {
        var rows = jdbc.queryForList("""
            SELECT r.member_id,m.name,COUNT(*) AS requests,
              SUM(CASE WHEN r.usage_seen THEN 1 ELSE 0 END) AS observed,
              SUM(CASE WHEN r.usage_complete THEN 1 ELSE 0 END) AS complete,
              COALESCE(SUM(CASE WHEN r.usage_seen THEN
                CAST(r.input_tokens AS NUMERIC)+r.output_tokens+r.cache_creation_input_tokens+r.cache_read_input_tokens
                ELSE 0 END),0) AS tokens
            FROM requests r JOIN members m ON m.id=r.member_id
            WHERE r.provider=? AND r.upstream_account=? AND r.started_at>=? AND r.started_at<?
            GROUP BY r.member_id,m.name ORDER BY tokens DESC,r.member_id
            """, provider, account, Timestamp.from(from), Timestamp.from(to));
        BigDecimal total = rows.stream().map(row -> new BigDecimal(row.get("tokens").toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return rows.stream().map(row -> {
            BigDecimal tokens = new BigDecimal(row.get("tokens").toString());
            BigDecimal share = total.signum() == 0 || AccountingService.number(row.get("observed")) == 0 ? null
                : tokens.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
            // Outside activity and provider weighting are unknown. Never multiply account usage by token share.
            return new Member(row.get("member_id").toString(), row.get("name").toString(), AccountingService.number(row.get("requests")),
                AccountingService.number(row.get("observed")), AccountingService.number(row.get("complete")), tokens, share, null);
        }).toList();
    }
}
