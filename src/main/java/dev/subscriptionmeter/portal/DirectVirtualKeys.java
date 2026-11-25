package dev.subscriptionmeter.portal;

import dev.subscriptionmeter.administration.AdminActionAudit;
import dev.subscriptionmeter.broker.BrokerStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DirectVirtualKeys {
    private static final String PURPOSE = "member_direct";
    private final JdbcTemplate jdbc;
    private final BrokerStore store;
    private final AdminActionAudit audit;
    private final String secretFile;

    public DirectVirtualKeys(JdbcTemplate jdbc, AdminActionAudit audit,
                            @Value("${meter.broker.admin-token-file:}") String secretFile) {
        this.jdbc = jdbc; this.audit = audit; this.secretFile = secretFile;
        this.store = new BrokerStore(jdbc, this::sealingSecret);
    }

    private Optional<String> sealingSecret() {
        if (!secretFile.isBlank()) try (var in = Files.newInputStream(Path.of(secretFile))) {
            String value = new String(in.readNBytes(1024), java.nio.charset.StandardCharsets.UTF_8).strip();
            if (value.matches("[A-Za-z0-9_-]{32,200}")) return Optional.of(value);
        } catch (IOException ignored) { }
        return Optional.empty();
    }

    @Transactional
    public void provision(String id, String admin, BigDecimal budget, int rpm, List<String> models, HttpServletRequest actor) {
        var rows = jdbc.queryForList("SELECT account_id,state,broker_key_id FROM virtual_key_requests WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row = rows.getFirst();
        if (row.get("broker_key_id") != null || !Set.of("pending", "approved_awaiting_provisioning").contains(row.get("state")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is not awaiting provisioning");
        String account = (String) row.get("account_id");
        var links = jdbc.queryForList("""
            SELECT m.id FROM portal_key_requests k JOIN members m ON m.id=k.member_id JOIN portal_accounts a ON a.id=k.account_id
            WHERE k.account_id=? AND k.state='active' AND m.active=TRUE AND a.role='member'
            """, String.class, account);
        if (links.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Member needs active workspace access");
        if (models.stream().anyMatch(model -> store.price(model).isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose priced broker models");
        if (sealingSecret().isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Key sealing unavailable");
        var issued = store.create("Direct API · " + id, PURPOSE, links.getFirst(), budget, rpm, String.join(",", models), 4096, null, admin);
        if (store.reveal(issued.id()).isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Key sealing unavailable");
        jdbc.update("UPDATE virtual_key_requests SET state='approved_awaiting_provisioning',decided_at=CURRENT_TIMESTAMP,broker_key_id=? WHERE id=?", issued.id(), id);
        audit.record(actor, "virtual_key_request.provision", id, Map.of("weeklyBudgetUsd", budget, "rpm", rpm, "allowedModels", models));
    }

    @Transactional
    public void revoke(String id, HttpServletRequest actor) {
        jdbc.queryForObject("SELECT name FROM coordination_locks WHERE name='openai_broker' FOR UPDATE", String.class);
        int changed = jdbc.update("UPDATE broker_keys SET active=FALSE,revoked_at=CURRENT_TIMESTAMP WHERE active=TRUE AND purpose=? AND id=(SELECT broker_key_id FROM virtual_key_requests WHERE id=?)", PURPOSE, id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "No active key to revoke");
        audit.record(actor, "virtual_key_request.revoke", id, Map.of());
    }

    private static final String ELIGIBLE = """
        SELECT b.id FROM virtual_key_requests r JOIN broker_keys b ON b.id=r.broker_key_id
        JOIN portal_accounts a ON a.id=r.account_id JOIN portal_key_requests k ON k.account_id=a.id AND k.member_id=b.member_id
        JOIN members m ON m.id=k.member_id
        WHERE r.state='approved_awaiting_provisioning' AND a.role='member' AND k.state='active' AND m.active=TRUE AND b.active=TRUE
          AND b.purpose='member_direct' AND (b.expires_at IS NULL OR b.expires_at>CURRENT_TIMESTAMP)
          AND b.weekly_budget_usd IS NOT NULL AND b.rpm IS NOT NULL AND NULLIF(TRIM(b.allowed_models),'') IS NOT NULL
        """;

    public String reveal(String account) {
        return jdbc.queryForList(ELIGIBLE + " AND r.account_id=?", String.class, account).stream().findFirst()
            .flatMap(store::reveal).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No usable direct API key"));
    }

    public record EmbeddingUsage(String model, long requests, long items, long promptTokens, long totalTokens) {}

    public List<EmbeddingUsage> embeddingUsage(String account) {
        return jdbc.query("""
            SELECT c.model, COUNT(*) AS requests, SUM(c.item_count) AS items,
                   SUM(c.input_tokens) AS prompt_tokens, SUM(c.total_tokens) AS total_tokens
            FROM broker_calls c
            WHERE c.key_id IN (""" + ELIGIBLE + " AND r.account_id=?)" + """
              AND c.operation='embeddings' AND c.state='settled' AND c.created_at>=?
            GROUP BY c.model ORDER BY c.model
            """, (rs, row) -> new EmbeddingUsage(rs.getString("model"), rs.getLong("requests"), rs.getLong("items"),
                rs.getLong("prompt_tokens"), rs.getLong("total_tokens")), account,
                java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(30))));
    }

    public boolean authorized(String token) {
        return store.authenticate(token, Instant.now()).filter(key -> key.purpose().equals(PURPOSE))
            .map(key -> !jdbc.queryForList(ELIGIBLE + " AND b.id=?", String.class, key.id()).isEmpty()).orElse(false);
    }
}
