package dev.subscriptionmeter;

import dev.subscriptionmeter.accounting.QuotaUsageService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.assertThat;

class QuotaUsageTest {
    final Instant now = Instant.parse("2026-11-08T12:00:00Z");
    JdbcTemplate jdbc;
    QuotaUsageService service;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:quota" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        ds.setUrl(ds.getUrl() + ";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("accounting-schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        service = new QuotaUsageService(jdbc);
        jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES('alice','Alice','a','sm_a',60,4),('bob','Bob','b','sm_b',60,4),('carol','Carol','c','sm_c',60,4)");
    }

    void snapshot(String id, String account, String bucket, int used, Instant observed, Instant reset, boolean trusted) {
        jdbc.update("INSERT INTO provider_quota_snapshots(id,provider,upstream_account,bucket,used_percentage,observed_at,resets_at,source,trusted) VALUES(?,'anthropic',?,?,?,?,?,'upstream_headers_v1',?)",
            id, account, bucket, used, Timestamp.from(observed), Timestamp.from(reset), trusted);
    }
    void request(String member, Instant started, String account, int input, boolean seen, boolean complete) {
        jdbc.update("INSERT INTO requests(id,member_id,started_at,path,model,upstream_account,input_tokens,usage_seen,usage_complete) VALUES(?,?,?,'/v1/messages','claude-test',?,?,?,?)",
            UUID.randomUUID().toString(), member, Timestamp.from(started), account, input, seen, complete);
    }

    @Test void alignsSessionAndWeeklyWindowsAndKeepsOutsideQuotaUnknown() {
        Instant sessionReset = now.plusSeconds(3600), sessionStart = sessionReset.minusSeconds(18000);
        Instant weeklyReset = now.plusSeconds(6 * 86400), weeklyStart = weeklyReset.minusSeconds(7 * 86400);
        snapshot("s", "primary", "five_hour", 25, now.minusSeconds(60), sessionReset, true);
        snapshot("w", "primary", "seven_day", 70, now.minusSeconds(60), weeklyReset, true);
        request("alice", weeklyStart, "primary", 100, true, true);
        request("bob", sessionStart, "primary", 300, true, true);
        request("alice", weeklyStart.minusNanos(1000), "primary", 9000, true, true);
        request("alice", now, "primary", 9000, true, true);
        request("alice", sessionStart, "another-account", 9000, true, true);
        var report = service.report(now, "");
        assertThat(report.outsideUsage()).isEqualTo("unknown");
        var session = report.windows().get(0);
        var weekly = report.windows().get(1);
        assertThat(session.startsAt()).isEqualTo(sessionStart);
        assertThat(session.requests()).isEqualTo(1);
        assertThat(session.members().getFirst().gatewayTokenSharePercent()).isEqualByComparingTo("100");
        assertThat(weekly.startsAt()).isEqualTo(weeklyStart);
        assertThat(weekly.requests()).isEqualTo(2);
        assertThat(weekly.members()).extracting(QuotaUsageService.Member::gatewayTokenSharePercent)
            .containsExactly(new BigDecimal("75.00"), new BigDecimal("25.00"));
        assertThat(weekly.members()).allSatisfy(member -> assertThat(member.allowanceUsedPercent()).isNull());
        assertThat(weekly.usedPercent()).isEqualByComparingTo("70");
    }

    @Test void staleObservationsRemainHistoricalWhileGatewayActivityCanBeNewer() {
        snapshot("s", "primary", "five_hour", 12, now.minusSeconds(600), now.plusSeconds(60), true);
        request("alice", now.minusSeconds(1), "primary", 100, true, true);
        var window = service.report(now, "anthropic").windows().getFirst();
        assertThat(window.freshness()).isEqualTo("stale");
        assertThat(window.observedAt()).isEqualTo(now.minusSeconds(600));
        assertThat(window.gatewayThrough()).isEqualTo(now);
        assertThat(window.requests()).isEqualTo(1);
        assertThat(window.usedPercent()).isEqualByComparingTo("12");
    }

    @Test void passedResetClosesHistoricalWindowWithoutInventingANewWindowOrZeroUsage() {
        Instant reset = now.minusSeconds(60);
        snapshot("w", "primary", "seven_day", 98, reset.minusSeconds(1), reset, true);
        request("alice", reset.minusSeconds(1), "primary", 100, true, true);
        request("bob", reset, "primary", 200, true, true);
        var window = service.report(now, "").windows().getFirst();
        assertThat(window.freshness()).isEqualTo("reset_passed");
        assertThat(window.gatewayThrough()).isEqualTo(reset);
        assertThat(window.requests()).isEqualTo(1);
        assertThat(window.usedPercent()).isEqualByComparingTo("98");
    }

    @Test void noTokensIsUnknownAndMissingRecordsDoNotPretendToUseZeroAllowance() {
        snapshot("w", "primary", "seven_day", 0, now, now.plusSeconds(86400), true);
        request("alice", now.minusSeconds(1), "primary", 0, true, true);
        request("carol", now.minusSeconds(1), "primary", 999, false, false);
        var empty = service.report(now, "").windows().getFirst();
        assertThat(empty.members()).allSatisfy(member -> assertThat(member.gatewayTokenSharePercent()).isNull());
        request("bob", now.minusSeconds(1), "primary", 100, true, false);
        var window = service.report(now, "").windows().getFirst();
        assertThat(window.reportedTokens()).isEqualByComparingTo("100");
        assertThat(window.usageObserved()).isEqualTo(2);
        assertThat(window.usageComplete()).isEqualTo(1);
        assertThat(window.members().stream().filter(member -> member.memberId().equals("carol")).findFirst().orElseThrow().gatewayTokenSharePercent()).isNull();
        assertThat(window.members()).allSatisfy(member -> assertThat(member.allowanceUsedPercent()).isNull());
    }

    @Test void newestTrustedSnapshotWinsPerAccountAndUnsupportedProvidersStayUnknown() {
        snapshot("old", "primary", "seven_day", 10, now.minusSeconds(2), now.plusSeconds(86400), true);
        snapshot("new", "primary", "seven_day", 20, now.minusSeconds(1), now.plusSeconds(86400), true);
        snapshot("untrusted", "primary", "seven_day", 80, now, now.plusSeconds(86400), false);
        snapshot("future", "primary", "seven_day", 90, now.plusSeconds(1), now.plusSeconds(86400), true);
        snapshot("other", "other", "seven_day", 50, now, now.plusSeconds(86400), true);
        var windows = service.report(now, "").windows();
        assertThat(windows).hasSize(2);
        assertThat(windows.stream().filter(w -> w.account().equals("primary")).findFirst().orElseThrow().usedPercent()).isEqualByComparingTo("20");
        assertThat(service.report(now, "openai").windows()).isEmpty();
        jdbc.update("DELETE FROM provider_quota_snapshots");
        assertThat(service.report(now, "").windows()).isEmpty();
    }

    @Test void invalidResetCannotProduceMemberAttribution() {
        snapshot("s", "primary", "five_hour", 12, now, now.plusSeconds(6 * 3600), true);
        var window = service.report(now, "").windows().getFirst();
        assertThat(window.freshness()).isEqualTo("invalid_window");
        assertThat(window.startsAt()).isNull();
        assertThat(window.members()).isEmpty();
    }

    @Test void tokenShareIncludesCachesOnceAndIsIsolatedFromOtherProviders() {
        snapshot("s", "primary", "five_hour", 12, now, now.plusSeconds(3600), true);
        request("alice", now.minusSeconds(1), "primary", 10, true, true);
        jdbc.update("UPDATE requests SET output_tokens=20,cache_creation_input_tokens=30,cache_read_input_tokens=40");
        request("bob", now.minusSeconds(1), "primary", 100, true, true);
        request("carol", now.minusSeconds(1), "primary", 9000, true, true);
        jdbc.update("UPDATE requests SET provider='openai' WHERE member_id='carol'");
        var window = service.report(now, "").windows().getFirst();
        assertThat(window.reportedTokens()).isEqualByComparingTo("200");
        assertThat(window.members()).hasSize(2).allSatisfy(member -> assertThat(member.gatewayTokenSharePercent()).isEqualByComparingTo("50"));
    }
}
