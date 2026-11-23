package dev.subscriptionmeter.summaries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Weekly spending cap for the OpenAI API key. Weeks run Monday 00:00 to Monday 00:00 UTC.
 * Each call reserves its worst-case cost under a database lock before it is sent, then settles
 * to the actual cost, so concurrent callers (including both deployment colors) cannot overshoot
 * the cap by more than the reserved estimates.
 */
@Service
public class OpenAIApiBudget {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    public record Settings(BigDecimal weeklyCapUsd, boolean summariesEnabled, String summaryModel,
                           BigDecimal inputUsdPerMTok, BigDecimal cachedInputUsdPerMTok, BigDecimal outputUsdPerMTok,
                           Instant updatedAt, String updatedBy) {}
    public record Usage(long input, long cachedInput, long output) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OpenAIApiBudget(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    public Settings settings() {
        return jdbc.queryForObject("SELECT * FROM openai_api_settings WHERE id=1", (rs, row) -> new Settings(
                rs.getBigDecimal("weekly_cap_usd"), rs.getBoolean("summaries_enabled"), rs.getString("summary_model"),
                rs.getBigDecimal("input_usd_per_mtok"), rs.getBigDecimal("cached_input_usd_per_mtok"),
                rs.getBigDecimal("output_usd_per_mtok"), rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by")));
    }

    public Settings update(BigDecimal cap, boolean enabled, String model, BigDecimal input, BigDecimal cached,
                           BigDecimal output, String actor) {
        jdbc.update("""
            UPDATE openai_api_settings SET weekly_cap_usd=?,summaries_enabled=?,summary_model=?,input_usd_per_mtok=?,
            cached_input_usd_per_mtok=?,output_usd_per_mtok=?,updated_at=?,updated_by=? WHERE id=1
            """, cap, enabled, model, input, cached, output, Timestamp.from(Instant.now()), actor);
        return settings();
    }

    public static Instant weekStart(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Settled cost plus outstanding reservations (and unknown-outcome calls) since the start of the week. */
    public BigDecimal spent(Instant now) {
        BigDecimal value = jdbc.queryForObject("""
            SELECT COALESCE(SUM(CASE WHEN state='settled' THEN cost_usd ELSE reserved_usd END),0) FROM openai_api_spend
            WHERE created_at>=? AND state IN ('reserved','settled','unknown')
            """, BigDecimal.class, Timestamp.from(weekStart(now)));
        return value == null ? BigDecimal.ZERO : value;
    }

    public static BigDecimal cost(Settings s, Usage usage) {
        long uncached = Math.max(0, usage.input() - usage.cachedInput());
        return s.inputUsdPerMTok().multiply(BigDecimal.valueOf(uncached))
                .add(s.cachedInputUsdPerMTok().multiply(BigDecimal.valueOf(Math.max(0, usage.cachedInput()))))
                .add(s.outputUsdPerMTok().multiply(BigDecimal.valueOf(Math.max(0, usage.output()))))
                .divide(MILLION, 6, RoundingMode.UP);
    }

    /** Returns a reservation id, or empty when the estimate would exceed this week's cap. */
    public Optional<String> reserve(String purpose, String reference, String model, BigDecimal estimate, Instant now) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT name FROM coordination_locks WHERE name='openai_budget' FOR UPDATE", String.class);
            BigDecimal cap = settings().weeklyCapUsd();
            if (spent(now).add(estimate).compareTo(cap) > 0) return Optional.<String>empty();
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO openai_api_spend(id,created_at,purpose,reference_id,model,state,reserved_usd) VALUES(?,?,?,?,?,'reserved',?)",
                    id, Timestamp.from(now), purpose, reference, model, estimate.setScale(6, RoundingMode.UP));
            return Optional.of(id);
        });
    }

    public BigDecimal settle(String id, Usage usage, Settings rates) {
        BigDecimal cost = cost(rates, usage);
        jdbc.update("UPDATE openai_api_spend SET state='settled',cost_usd=?,input_tokens=?,cached_input_tokens=?,output_tokens=? WHERE id=?",
                cost, usage.input(), usage.cachedInput(), usage.output(), id);
        return cost;
    }

    /** The provider answered with an error before generating, so nothing was billed. */
    public void release(String id) {
        jdbc.update("UPDATE openai_api_spend SET state='failed',cost_usd=0 WHERE id=?", id);
    }

    /** Outcome unknown (timeout, broken connection): keep counting the reservation, conservatively. */
    public void unknown(String id) {
        jdbc.update("UPDATE openai_api_spend SET state='unknown' WHERE id=?", id);
    }

    public Map<String, Object> status(Instant now, boolean keyConfigured) {
        Settings s = settings();
        BigDecimal spent = spent(now);
        var result = new LinkedHashMap<String, Object>();
        result.put("keyConfigured", keyConfigured);
        result.put("weeklyCapUsd", s.weeklyCapUsd());
        result.put("summariesEnabled", s.summariesEnabled());
        result.put("summaryModel", s.summaryModel());
        result.put("inputUsdPerMTok", s.inputUsdPerMTok());
        result.put("cachedInputUsdPerMTok", s.cachedInputUsdPerMTok());
        result.put("outputUsdPerMTok", s.outputUsdPerMTok());
        result.put("updatedAt", s.updatedAt());
        result.put("updatedBy", s.updatedBy());
        Instant start = weekStart(now);
        result.put("weekStart", start);
        result.put("weekEnd", start.plus(Duration.ofDays(7)));
        result.put("spentUsd", spent);
        result.put("remainingUsd", s.weeklyCapUsd().subtract(spent).max(BigDecimal.ZERO));
        result.put("recent", jdbc.queryForList("""
            SELECT created_at AS "createdAt",purpose,model,state,reserved_usd AS "reservedUsd",cost_usd AS "costUsd",
            input_tokens AS "inputTokens",cached_input_tokens AS "cachedInputTokens",output_tokens AS "outputTokens"
            FROM openai_api_spend ORDER BY created_at DESC LIMIT 20
            """));
        return result;
    }
}
