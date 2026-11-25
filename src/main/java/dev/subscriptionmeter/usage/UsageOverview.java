package dev.subscriptionmeter.usage;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Aggregates the gateway request ledger only, never captures, summaries, or costs. */
@Service
public class UsageOverview {
    public record TierUsage(String tier, long requests, long tokens) {}
    public record LeaderboardEntry(String label, long requests, long tokens, boolean you) {}
    public record Result(List<TierUsage> own, List<LeaderboardEntry> leaderboard, int days) {}

    private static final List<String> TIERS = List.of("claude", "codex", "paid", "other");
    private static final String VIEWER = """
        WITH viewer AS (
            SELECT k.member_id, (k.state = 'active' AND m.active) AS can_view_team
            FROM portal_key_requests k JOIN members m ON m.id = k.member_id
            WHERE k.account_id = ?
        )
        """;
    private static final String SELECT = """
        SELECT member_id, own_usage, ranked, tier, COUNT(*) AS requests, SUM(tokens) AS tokens,
               (SELECT MIN(a.display_name) FROM portal_key_requests k JOIN portal_accounts a ON a.id = k.account_id
                WHERE k.member_id = usage_rows.member_id) AS display_name
        FROM (
            SELECT r.member_id, %s AS own_usage, %s AS ranked,
                CASE
                    WHEN r.provider = 'anthropic' THEN 'claude'
                    WHEN r.provider = 'openai' AND (r.upstream_account = 'broker' OR d.kind = 'broker') THEN 'paid'
                    WHEN r.provider = 'openai' AND (r.upstream_account = 'codex' OR d.kind = 'codex') THEN 'codex'
                    ELSE 'other'
                END AS tier,
                r.input_tokens + r.output_tokens + r.cache_creation_input_tokens + r.cache_read_input_tokens AS tokens
            FROM requests r
            LEFT JOIN routing_deployments d ON d.account = r.upstream_account
            %s
            WHERE r.path = '/v1/messages' AND r.started_at >= ? AND r.started_at < ?
            %s
        ) usage_rows
        GROUP BY member_id, own_usage, ranked, tier
        """;
    // Own usage is not conditional on access still being active. The active-access check and the
    // selection of leaderboard rows share the same database snapshot, even during revocation.
    private static final String PORTAL = VIEWER + SELECT.formatted("r.member_id = v.member_id", "v.can_view_team",
            "JOIN members m ON m.id = r.member_id CROSS JOIN viewer v",
            "AND (r.member_id = v.member_id OR (v.can_view_team AND m.active))");
    private static final String ADMIN = SELECT.formatted("TRUE", "TRUE", "", "");

    private final JdbcTemplate jdbc;

    public UsageOverview(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Result forAccount(String accountId, int days) {
        return read(accountId, days, false);
    }

    public Result forAdmin(int days) {
        return read(null, days, true);
    }

    private record Totals(long requests, long tokens) {
        Totals plus(Totals other) { return new Totals(requests + other.requests, tokens + other.tokens); }
    }
    private record RankedMember(String id, String displayName, boolean you, Totals totals) {}

    private Result read(String accountId, int days, boolean admin) {
        Instant now = Instant.now();
        Timestamp from = Timestamp.from(now.minus(days, ChronoUnit.DAYS));
        Timestamp through = Timestamp.from(now);
        var tiers = new HashMap<String, Totals>();
        var ranked = new HashMap<String, RankedMember>();
        // Count one row per admitted request, regardless of outcome or whether usage was observed.
        // The normalized ledger counters keep cached and uncached input disjoint for both protocols.
        jdbc.query(admin ? ADMIN : PORTAL, rs -> {
            var usage = new Totals(rs.getLong("requests"), rs.getLong("tokens"));
            boolean own = rs.getBoolean("own_usage");
            if (own) tiers.merge(rs.getString("tier"), usage, Totals::plus);
            if (rs.getBoolean("ranked")) {
                String id = rs.getString("member_id");
                ranked.merge(id, new RankedMember(id, rs.getString("display_name"), own && !admin, usage),
                        (a, b) -> new RankedMember(a.id(), a.displayName(), a.you(), a.totals().plus(b.totals())));
            }
        }, admin ? new Object[]{from, through} : new Object[]{accountId, from, through});

        var own = TIERS.stream().map(tier -> {
            var t = tiers.getOrDefault(tier, new Totals(0, 0));
            return new TierUsage(tier, t.requests(), t.tokens());
        }).toList();
        var ordered = ranked.values().stream().sorted(Comparator
                .comparingLong((RankedMember member) -> member.totals().tokens()).reversed()
                .thenComparing(Comparator.comparingLong((RankedMember member) -> member.totals().requests()).reversed())
                .thenComparing(RankedMember::id)).toList();
        var leaderboard = new ArrayList<LeaderboardEntry>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            var member = ordered.get(i);
            leaderboard.add(new LeaderboardEntry(member.displayName() == null ? "Member " + (i + 1) : member.displayName(),
                    member.totals().requests(), member.totals().tokens(), member.you()));
        }
        return new Result(own, List.copyOf(leaderboard), days);
    }
}
