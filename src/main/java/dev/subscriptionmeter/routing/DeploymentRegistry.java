package dev.subscriptionmeter.routing;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The routing deployments, read at most every few seconds per instance. */
@Component
public class DeploymentRegistry {
    public static final Set<String> SEEDED = Set.of("claude-primary", "codex-primary", "openai-api-key");
    /** Extra account credentials must live in the dedicated read-only mount. */
    static final Pattern CREDENTIAL = Pattern.compile("/run/control-usage-extra/[A-Za-z0-9._-]{1,100}");
    private static final Duration TTL = Duration.ofSeconds(5);
    private final JdbcTemplate jdbc;
    private volatile List<Deployment> cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public DeploymentRegistry(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Integer integer(Object v) { return v == null ? null : ((Number) v).intValue(); }

    public List<Deployment> all() {
        Instant now = Instant.now();
        var c = cached;
        if (c != null && cachedAt.plus(TTL).isAfter(now)) return c;
        c = jdbc.queryForList("SELECT * FROM routing_deployments ORDER BY priority, id").stream().map(r -> new Deployment(
                (String) r.get("id"), (String) r.get("name"), (String) r.get("kind"), (String) r.get("account"),
                ((Number) r.get("priority")).intValue(), ((Number) r.get("weight")).intValue(), Boolean.TRUE.equals(r.get("enabled")),
                integer(r.get("max_concurrency")), integer(r.get("context_window_tokens")), (String) r.get("credential_file"))).toList();
        cached = c; cachedAt = now;
        return c;
    }

    public Optional<Deployment> find(String id) { return all().stream().filter(d -> d.id().equals(id)).findFirst(); }

    public static final class Invalid extends Exception { Invalid(String m) { super(m); } }

    static void validate(Deployment d) throws Invalid {
        if (!d.id().matches("[a-z0-9-]{3,40}")) throw new Invalid("Id must be 3-40 lowercase letters, digits or dashes");
        if (!Set.of("anthropic", "codex", "broker").contains(d.kind())) throw new Invalid("Unknown kind");
        if (!d.account().matches("[a-z0-9-]{2,80}")) throw new Invalid("Invalid account label");
        if (d.priority() < 1 || d.priority() > 9 || d.weight() < 1 || d.weight() > 1000) throw new Invalid("Priority 1-9 and weight 1-1000 required");
        if (d.maxConcurrency() != null && (d.maxConcurrency() < 1 || d.maxConcurrency() > 1000)) throw new Invalid("Invalid concurrency cap");
        if (d.contextWindowTokens() != null && (d.contextWindowTokens() < 1000 || d.contextWindowTokens() > 10_000_000)) throw new Invalid("Invalid context window");
        if (d.credentialFile() != null && (d.broker() || !CREDENTIAL.matcher(d.credentialFile()).matches()))
            throw new Invalid("Extra credentials must be a file under /run/control-usage-extra/ (not for the broker)");
        if (d.broker() && !d.id().equals("openai-api-key")) throw new Invalid("There is one broker deployment; manage its keys under OpenAI API");
    }

    public void save(Deployment d, boolean creating, String actor) throws Invalid {
        validate(d);
        if (creating) {
            if (find(d.id()).isPresent()) throw new Invalid("A deployment with this id exists");
            if (all().stream().anyMatch(x -> x.account().equals(d.account()))) throw new Invalid("Account label already used");
            if (d.credentialFile() == null) throw new Invalid("A new account needs its own credential file");
            jdbc.update("""
                INSERT INTO routing_deployments(id,name,kind,account,priority,weight,enabled,max_concurrency,context_window_tokens,credential_file,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, d.id(), d.name(), d.kind(), d.account(), d.priority(), d.weight(), d.enabled(), d.maxConcurrency(), d.contextWindowTokens(), d.credentialFile(), actor);
        } else {
            var existing = find(d.id()).orElseThrow(() -> new Invalid("Unknown deployment"));
            if (!existing.kind().equals(d.kind()) || !existing.account().equals(d.account())) throw new Invalid("Kind and account cannot change");
            if (SEEDED.contains(d.id()) && !Objects.equals(existing.credentialFile(), d.credentialFile())) throw new Invalid("Primary credentials are fixed");
            jdbc.update("""
                UPDATE routing_deployments SET name=?,priority=?,weight=?,enabled=?,max_concurrency=?,context_window_tokens=?,credential_file=?,
                updated_at=?,updated_by=? WHERE id=?
                """, d.name(), d.priority(), d.weight(), d.enabled(), d.maxConcurrency(), d.contextWindowTokens(), d.credentialFile(),
                    Timestamp.from(Instant.now()), actor, d.id());
        }
        cached = null;
    }

    public void delete(String id) throws Invalid {
        if (SEEDED.contains(id)) throw new Invalid("The primary deployments can be disabled but not deleted");
        if (jdbc.update("DELETE FROM routing_deployments WHERE id=?", id) != 1) throw new Invalid("Unknown deployment");
        cached = null;
    }
}
