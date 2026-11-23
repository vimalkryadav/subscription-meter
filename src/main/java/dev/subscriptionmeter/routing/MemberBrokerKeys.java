package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.accounting.AccountingService;
import dev.subscriptionmeter.security.MemberKeyVault;
import dev.subscriptionmeter.summaries.BrokerAdminClient;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each member's broker virtual key for the last fallback tier, created on first use with the default
 * weekly budget. The gateway keeps an AES-GCM sealed copy so it can present the key to the broker.
 */
@Component
public class MemberBrokerKeys {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MemberKeyVault vault;
    private final BrokerAdminClient broker;
    private final RoutingSettings settings;
    private final AccountingService log;

    public MemberBrokerKeys(JdbcTemplate jdbc, MemberKeyVault vault, BrokerAdminClient broker, RoutingSettings settings, AccountingService log) {
        this.jdbc = jdbc; this.vault = vault; this.broker = broker; this.settings = settings; this.log = log;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    private Optional<String> existing(String memberId) {
        return jdbc.queryForList("SELECT key_sealed FROM member_broker_keys WHERE member_id=?", String.class, memberId)
                .stream().findFirst().flatMap(sealed -> vault.open("member-broker:" + memberId, sealed));
    }

    public Optional<String> forMember(String memberId) {
        var found = existing(memberId);
        if (found.isPresent()) return found;
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT name FROM coordination_locks WHERE name='member_broker_keys' FOR UPDATE", String.class);
            var again = existing(memberId);
            if (again.isPresent()) return again;
            if (!jdbc.queryForList("SELECT 1 FROM member_broker_keys WHERE member_id=?", memberId).isEmpty())
                return Optional.<String>empty(); // sealed under an older admin secret: an administrator must reissue
            String name = jdbc.queryForList("SELECT name FROM members WHERE id=?", String.class, memberId).stream().findFirst().orElse(memberId);
            var request = new LinkedHashMap<String, Object>();
            request.put("name", ("Fallback · " + name).substring(0, Math.min(100, ("Fallback · " + name).length())));
            request.put("purpose", "member_fallback");
            request.put("memberId", memberId);
            request.put("weeklyBudgetUsd", settings.get().memberWeeklyBudgetUsd());
            request.put("maxOutputTokens", 64000);
            var reply = broker.call("POST", "/admin/keys", JSON.writeValueAsString(request), "system:fallback");
            if (reply.status() != 201) return Optional.<String>empty();
            var created = JSON.readTree(reply.body());
            String key = created.path("key").asString(""), id = created.path("id").asString("");
            if (!key.startsWith("cuvk_") || id.isEmpty()) return Optional.<String>empty();
            jdbc.update("INSERT INTO member_broker_keys(member_id,broker_key_id,key_sealed) VALUES(?,?,?)", memberId, id, vault.seal("member-broker:" + memberId, key));
            log.log("system:fallback", "broker.key.auto_create", id, Map.of("memberId", memberId, "weeklyBudgetUsd", String.valueOf(request.get("weeklyBudgetUsd"))));
            return Optional.of(key);
        });
    }
}
