package dev.subscriptionmeter.broker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Broker persistence: virtual keys (stored only as SHA-256 hashes), model prices, the global weekly
 * cap and the call ledger. Admission checks run under one database lock so concurrent calls cannot
 * jointly exceed a key budget or the global cap by more than their reserved worst-case estimates.
 */
public final class BrokerStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Key(String id, String name, String purpose, String memberId, String prefix, boolean active,
                      BigDecimal weeklyBudgetUsd, Integer rpm, List<String> allowedModels, int maxOutputTokens, Instant expiresAt) {
        public boolean allows(String model) { return allowedModels.isEmpty() || allowedModels.contains(model); }
    }
    public record Price(String model, BigDecimal input, BigDecimal cachedInput, BigDecimal output,
                        Integer longContextTokens, BigDecimal longInput, BigDecimal longCachedInput, BigDecimal longOutput) {}
    public record Usage(long input, long cachedInput, long output) {}
    public record Admission(String callId, String denial, String message) {
        static Admission allowed(String id) { return new Admission(id, null, null); }
        static Admission denied(String id, String type, String message) { return new Admission(id, type, message); }
        public boolean ok() { return denial == null; }
    }
    public record IssuedKey(String id, String key, String prefix) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final java.util.function.Supplier<Optional<String>> sealingSecret;

    /**
     * @param sealingSecret the broker admin token. Virtual keys are kept as an AES-GCM sealed copy
     *        (keyed from it) so administrators can view them again; authentication uses only the hash.
     */
    public BrokerStore(JdbcTemplate jdbc, java.util.function.Supplier<Optional<String>> sealingSecret) {
        this.jdbc = jdbc;
        this.sealingSecret = sealingSecret;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static Instant weekStart(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof Instant i) return i;
        throw new IllegalArgumentException("Unsupported timestamp");
    }

    private static List<String> models(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private Key key(Map<String, Object> r) {
        return new Key((String) r.get("id"), (String) r.get("name"), (String) r.get("purpose"), (String) r.get("member_id"),
                (String) r.get("key_prefix"), Boolean.TRUE.equals(r.get("active")), (BigDecimal) r.get("weekly_budget_usd"),
                r.get("rpm") == null ? null : ((Number) r.get("rpm")).intValue(), models((String) r.get("allowed_models")),
                ((Number) r.get("max_output_tokens")).intValue(), instant(r.get("expires_at")));
    }

    /** Returns the key only if it exists, is active and unexpired. */
    public Optional<Key> authenticate(String presented, Instant now) {
        if (presented == null || !presented.startsWith("cuvk_") || presented.length() > 200) return Optional.empty();
        return jdbc.queryForList("SELECT * FROM broker_keys WHERE key_hash=?", hash(presented)).stream().findFirst().map(this::key)
                .filter(k -> k.active() && (k.expiresAt() == null || k.expiresAt().isAfter(now)));
    }

    public Optional<Price> price(String model) {
        return jdbc.queryForList("SELECT * FROM broker_model_prices WHERE model=?", model).stream().findFirst().map(r -> new Price(
                (String) r.get("model"), (BigDecimal) r.get("input_usd_per_mtok"), (BigDecimal) r.get("cached_input_usd_per_mtok"),
                (BigDecimal) r.get("output_usd_per_mtok"), r.get("long_context_tokens") == null ? null : ((Number) r.get("long_context_tokens")).intValue(),
                (BigDecimal) r.get("long_input_usd_per_mtok"), (BigDecimal) r.get("long_cached_input_usd_per_mtok"), (BigDecimal) r.get("long_output_usd_per_mtok")));
    }

    public static BigDecimal cost(Price p, Usage u) {
        boolean longContext = p.longContextTokens() != null && u.input() > p.longContextTokens() && p.longInput() != null;
        BigDecimal in = longContext ? p.longInput() : p.input();
        BigDecimal cached = longContext && p.longCachedInput() != null ? p.longCachedInput() : p.cachedInput();
        BigDecimal out = longContext && p.longOutput() != null ? p.longOutput() : p.output();
        long uncached = Math.max(0, u.input() - Math.max(0, u.cachedInput()));
        return in.multiply(BigDecimal.valueOf(uncached)).add(cached.multiply(BigDecimal.valueOf(Math.max(0, u.cachedInput()))))
                .add(out.multiply(BigDecimal.valueOf(Math.max(0, u.output())))).divide(MILLION, 6, RoundingMode.UP);
    }

    private static final String SPENT = "COALESCE(SUM(CASE WHEN state='settled' THEN cost_usd WHEN state IN ('reserved','unknown') THEN reserved_usd ELSE 0 END),0)";

    public BigDecimal spent(Instant now, String keyId) {
        BigDecimal v = keyId == null
                ? jdbc.queryForObject("SELECT " + SPENT + " FROM broker_calls WHERE created_at>=?", BigDecimal.class, Timestamp.from(weekStart(now)))
                : jdbc.queryForObject("SELECT " + SPENT + " FROM broker_calls WHERE key_id=? AND created_at>=?", BigDecimal.class, keyId, Timestamp.from(weekStart(now)));
        return v == null ? BigDecimal.ZERO : v;
    }

    public BigDecimal globalCap() {
        return jdbc.queryForObject("SELECT weekly_cap_usd FROM broker_settings WHERE id=1", BigDecimal.class);
    }

    /** Records a denied call (for tracking) without reserving anything. */
    public Admission deny(Key key, String model, boolean streaming, Instant now, int status, String type, String message) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO broker_calls(id,created_at,key_id,purpose,member_id,model,streaming,state,status_code,reserved_usd,cost_usd,error_type) VALUES(?,?,?,?,?,?,?,'denied',?,0,0,?)",
                id, Timestamp.from(now), key == null ? null : key.id(), key == null ? null : key.purpose(), key == null ? null : key.memberId(),
                model == null ? null : model.substring(0, Math.min(100, model.length())), streaming, status, type);
        return Admission.denied(id, type, message);
    }

    /** Checks rate limit, key budget and global cap, then reserves the estimate, atomically. */
    public Admission admit(Key key, String model, boolean streaming, BigDecimal estimate, Instant now) {
        return admit(key, model, streaming, estimate, now, null);
    }

    Admission admit(Key key, String model, boolean streaming, BigDecimal estimate, Instant now, Integer items) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT name FROM coordination_locks WHERE name='openai_broker' FOR UPDATE", String.class);
            if (items != null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM broker_keys b JOIN virtual_key_requests r ON r.broker_key_id=b.id JOIN portal_accounts a ON a.id=r.account_id JOIN portal_key_requests k ON k.account_id=r.account_id AND k.member_id=b.member_id JOIN members m ON m.id=k.member_id WHERE b.id=? AND b.active AND b.purpose='member_direct' AND (b.expires_at IS NULL OR b.expires_at>CURRENT_TIMESTAMP) AND b.weekly_budget_usd>0 AND b.rpm>0 AND a.role='member' AND k.state='active' AND m.active)", Boolean.class, key.id())))
                return Admission.denied(null, "invalid_api_key", "Direct API key is invalid or inactive.");
            if (key.rpm() != null) {
                Integer recent = jdbc.queryForObject("SELECT COUNT(*) FROM broker_calls WHERE key_id=? AND created_at>? AND state<>'denied'",
                        Integer.class, key.id(), Timestamp.from(now.minusSeconds(60)));
                if (recent != null && recent >= key.rpm())
                    return deny(key, model, streaming, now, 429, "broker_rate_limited", "This key's requests-per-minute limit was reached.");
            }
            if (key.weeklyBudgetUsd() != null && spent(now, key.id()).add(estimate).compareTo(key.weeklyBudgetUsd()) > 0)
                return deny(key, model, streaming, now, 429, "broker_key_budget_exhausted", "This key's weekly budget is used up.");
            if (spent(now, null).add(estimate).compareTo(globalCap()) > 0)
                return deny(key, model, streaming, now, 429, "broker_weekly_cap_reached", "The weekly OpenAI spending cap is reached.");
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO broker_calls(id,created_at,key_id,purpose,member_id,model,streaming,state,reserved_usd) VALUES(?,?,?,?,?,?,?,'reserved',?)",
                    id, Timestamp.from(now), key.id(), key.purpose(), key.memberId(), model, streaming, estimate.setScale(6, RoundingMode.UP));
            if (items != null) jdbc.update("UPDATE broker_calls SET operation='embeddings',item_count=? WHERE id=?", items, id);
            return Admission.allowed(id);
        });
    }

    public void settle(String callId, int status, Usage usage, BigDecimal cost, long durationMs, String upstreamId) {
        jdbc.update("UPDATE broker_calls SET state='settled',status_code=?,cost_usd=?,input_tokens=?,cached_input_tokens=?,output_tokens=?,duration_ms=?,upstream_request_id=? WHERE id=?",
                status, cost, usage.input(), usage.cachedInput(), usage.output(), durationMs, upstreamId, callId);
    }

    void settleEmbedding(String id, long prompt, long total, BigDecimal cost, long durationMs) {
        jdbc.update("UPDATE broker_calls SET state='settled',status_code=200,cost_usd=?,input_tokens=?,total_tokens=?,duration_ms=? WHERE id=?",
                cost, prompt, total, durationMs, id);
    }

    /** The provider refused before generating: nothing billed. */
    public void failed(String callId, int status, long durationMs, String upstreamId, String errorType) {
        jdbc.update("UPDATE broker_calls SET state='failed',status_code=?,cost_usd=0,duration_ms=?,upstream_request_id=?,error_type=? WHERE id=?",
                status, durationMs, upstreamId, errorType, callId);
    }

    /** Outcome or usage unknown: the reservation keeps counting, conservatively. */
    public void unknown(String callId, Integer status, long durationMs, String upstreamId, String errorType) {
        jdbc.update("UPDATE broker_calls SET state='unknown',status_code=?,duration_ms=?,upstream_request_id=?,error_type=? WHERE id=?",
                status, durationMs, upstreamId, errorType, callId);
    }

    // ---- Administration ----

    public void log(String actor, String action, String target, Map<String, ?> details) {
        jdbc.update("INSERT INTO broker_admin_log(id,actor,action,target,details_json) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(), actor, action, target, JSON.writeValueAsString(details));
    }

    public IssuedKey create(String name, String purpose, String memberId, BigDecimal budget, Integer rpm, String models,
                            int maxOutputTokens, Instant expiresAt, String actor) {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String key = "cuvk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString(), prefix = key.substring(0, 12);
        String sealed = vault().map(v -> v.seal("broker:" + id, key)).orElse(null);
        jdbc.update("INSERT INTO broker_keys(id,name,purpose,member_id,key_hash,key_prefix,key_sealed,weekly_budget_usd,rpm,allowed_models,max_output_tokens,expires_at,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, name, purpose, memberId, hash(key), prefix, sealed, budget, rpm, models, maxOutputTokens, expiresAt == null ? null : Timestamp.from(expiresAt), actor);
        return new IssuedKey(id, key, prefix);
    }

    private Optional<dev.subscriptionmeter.security.MemberKeyVault> vault() {
        return sealingSecret.get().filter(s -> s.length() >= 32).map(dev.subscriptionmeter.security.MemberKeyVault::forSecret);
    }

    /** Empty if the key is unknown, or was sealed under a different admin token. */
    public Optional<String> reveal(String id) {
        var rows = jdbc.queryForList("SELECT key_sealed FROM broker_keys WHERE id=?", String.class, id);
        if (rows.isEmpty() || rows.getFirst() == null) return Optional.empty();
        return vault().flatMap(v -> v.open("broker:" + id, rows.getFirst()));
    }

    public boolean exists(String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM broker_keys WHERE id=?", Integer.class, id);
        return n != null && n > 0;
    }

    public boolean update(String id, String name, BigDecimal budget, Integer rpm, String models, int maxOutputTokens, Instant expiresAt, boolean active) {
        return jdbc.update("UPDATE broker_keys SET name=?,weekly_budget_usd=?,rpm=?,allowed_models=?,max_output_tokens=?,expires_at=?,active=?,revoked_at=CASE WHEN ? THEN NULL ELSE COALESCE(revoked_at,CURRENT_TIMESTAMP) END WHERE id=?",
                name, budget, rpm, models, maxOutputTokens, expiresAt == null ? null : Timestamp.from(expiresAt), active, active, id) == 1;
    }

    public void setCap(BigDecimal cap, String actor) {
        jdbc.update("UPDATE broker_settings SET weekly_cap_usd=?,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE id=1", cap, actor);
    }

    public void setPrice(Price p, String actor) {
        if (jdbc.update("""
                UPDATE broker_model_prices SET input_usd_per_mtok=?,cached_input_usd_per_mtok=?,output_usd_per_mtok=?,long_context_tokens=?,
                long_input_usd_per_mtok=?,long_cached_input_usd_per_mtok=?,long_output_usd_per_mtok=?,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE model=?
                """, p.input(), p.cachedInput(), p.output(), p.longContextTokens(), p.longInput(), p.longCachedInput(), p.longOutput(), actor, p.model()) == 0)
            jdbc.update("""
                INSERT INTO broker_model_prices(model,input_usd_per_mtok,cached_input_usd_per_mtok,output_usd_per_mtok,long_context_tokens,
                long_input_usd_per_mtok,long_cached_input_usd_per_mtok,long_output_usd_per_mtok,updated_by) VALUES(?,?,?,?,?,?,?,?,?)
                """, p.model(), p.input(), p.cachedInput(), p.output(), p.longContextTokens(), p.longInput(), p.longCachedInput(), p.longOutput(), actor);
    }

    public boolean deletePrice(String model) { return jdbc.update("DELETE FROM broker_model_prices WHERE model=?", model) == 1; }

    public LinkedHashMap<String, Object> state(Instant now, boolean realKeyConfigured) {
        var result = new LinkedHashMap<String, Object>();
        Instant start = weekStart(now);
        BigDecimal cap = globalCap(), spent = spent(now, null);
        result.put("realKeyConfigured", realKeyConfigured);
        result.put("weeklyCapUsd", cap);
        result.put("weekStart", start.toString());
        result.put("weekEnd", start.plus(Duration.ofDays(7)).toString());
        result.put("spentUsd", spent);
        result.put("remainingUsd", cap.subtract(spent).max(BigDecimal.ZERO));
        result.put("settings", jdbc.queryForMap("SELECT updated_at AS \"updatedAt\",updated_by AS \"updatedBy\" FROM broker_settings WHERE id=1"));
        var keys = new ArrayList<Map<String, Object>>();
        for (var r : jdbc.queryForList("SELECT * FROM broker_keys ORDER BY active DESC,created_at")) {
            var k = key(r);
            var row = new LinkedHashMap<String, Object>();
            row.put("id", k.id()); row.put("name", k.name()); row.put("purpose", k.purpose()); row.put("memberId", k.memberId());
            row.put("prefix", k.prefix()); row.put("active", k.active()); row.put("viewable", r.get("key_sealed") != null); row.put("weeklyBudgetUsd", k.weeklyBudgetUsd());
            row.put("rpm", k.rpm()); row.put("allowedModels", k.allowedModels()); row.put("maxOutputTokens", k.maxOutputTokens());
            row.put("expiresAt", k.expiresAt() == null ? null : k.expiresAt().toString());
            row.put("createdAt", instant(r.get("created_at")).toString()); row.put("createdBy", r.get("created_by"));
            row.put("spentUsd", spent(now, k.id()));
            row.put("callsThisWeek", jdbc.queryForObject("SELECT COUNT(*) FROM broker_calls WHERE key_id=? AND created_at>=?", Long.class, k.id(), Timestamp.from(start)));
            keys.add(row);
        }
        result.put("keys", keys);
        result.put("prices", jdbc.queryForList("""
            SELECT model,input_usd_per_mtok AS "inputUsdPerMTok",cached_input_usd_per_mtok AS "cachedInputUsdPerMTok",output_usd_per_mtok AS "outputUsdPerMTok",
            long_context_tokens AS "longContextTokens",long_input_usd_per_mtok AS "longInputUsdPerMTok",long_cached_input_usd_per_mtok AS "longCachedInputUsdPerMTok",
            long_output_usd_per_mtok AS "longOutputUsdPerMTok",updated_by AS "updatedBy" FROM broker_model_prices ORDER BY model
            """));
        return result;
    }

    public List<Map<String, Object>> calls(String keyId, int limit) {
        return jdbc.queryForList("""
            SELECT c.id,c.created_at AS "createdAt",c.key_id AS "keyId",k.name AS "keyName",c.purpose,c.member_id AS "memberId",c.model,c.streaming,
            c.state,c.status_code AS "statusCode",c.reserved_usd AS "reservedUsd",c.cost_usd AS "costUsd",c.input_tokens AS "inputTokens",
            c.cached_input_tokens AS "cachedInputTokens",c.output_tokens AS "outputTokens",c.duration_ms AS "durationMs",c.error_type AS "errorType"
            FROM broker_calls c LEFT JOIN broker_keys k ON k.id=c.key_id WHERE (?='' OR c.key_id=?) ORDER BY c.created_at DESC LIMIT ?
            """, keyId, keyId, limit);
    }

    public List<Map<String, Object>> adminLog(int limit) {
        return jdbc.queryForList("SELECT occurred_at AS \"occurredAt\",actor,action,target,details_json AS \"details\" FROM broker_admin_log ORDER BY occurred_at DESC LIMIT ?", limit);
    }
}
