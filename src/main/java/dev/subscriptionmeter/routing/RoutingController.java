package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.administration.AdminActionAudit;
import dev.subscriptionmeter.portal.PortalAuthFilter;
import dev.subscriptionmeter.portal.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Administrator-only (under /admin): routing strategy, reliability settings, deployments and their live health. */
@RestController
@RequestMapping("/admin/routing")
public class RoutingController {
    private final RoutingSettings settings;
    private final RoutingState state;
    private final DeploymentRegistry registry;
    private final DeploymentHealth health;
    private final DeploymentClients clients;
    private final QuotaBalancer balancer;
    private final JdbcTemplate jdbc;
    private final AdminActionAudit audit;
    private final SessionService sessions;

    public RoutingController(RoutingSettings settings, RoutingState state, DeploymentRegistry registry, DeploymentHealth health,
                             DeploymentClients clients, QuotaBalancer balancer, JdbcTemplate jdbc, AdminActionAudit audit, SessionService sessions) {
        this.settings = settings; this.state = state; this.registry = registry; this.health = health; this.clients = clients;
        this.balancer = balancer; this.jdbc = jdbc; this.audit = audit; this.sessions = sessions;
    }

    private String actor(HttpServletRequest request) { return PortalAuthFilter.cookie(request) == null ? "bootstrap" : sessions.actor(request); }

    @GetMapping
    public Map<String, Object> status() {
        var s = settings.get();
        Instant now = Instant.now();
        var result = new LinkedHashMap<String, Object>();
        result.put("gptModelsEnabled", s.gptModelsEnabled());
        result.put("fallbackEnabled", s.fallbackEnabled());
        result.put("strategy", s.strategy());
        result.put("mapOpus", s.mapOpus()); result.put("mapSonnet", s.mapSonnet()); result.put("mapHaiku", s.mapHaiku());
        result.put("memberWeeklyBudgetUsd", s.memberWeeklyBudgetUsd());
        result.put("allowedFails", s.allowedFails()); result.put("cooldownSeconds", s.cooldownSeconds());
        result.put("transientRetries", s.transientRetries()); result.put("firstByteTimeoutSeconds", s.firstByteTimeoutSeconds());
        result.put("updatedAt", s.updatedAt().toString()); result.put("updatedBy", s.updatedBy());
        var deployments = new ArrayList<Map<String, Object>>();
        var sessionCounts = state.sessionsByDeployment(now);
        long onClaude = 0, onCodex = 0, onBroker = 0;
        for (var d : registry.all()) {
            long n = sessionCounts.getOrDefault(d.id(), 0L);
            if (d.anthropic()) onClaude += n; else if (d.codex()) onCodex += n; else onBroker += n;
            var h = health.view(d.id());
            var row = new LinkedHashMap<String, Object>();
            row.put("id", d.id()); row.put("name", d.name()); row.put("kind", d.kind()); row.put("account", d.account());
            row.put("priority", d.priority()); row.put("weight", d.weight()); row.put("enabled", d.enabled());
            row.put("maxConcurrency", d.maxConcurrency()); row.put("contextWindowTokens", d.contextWindowTokens());
            row.put("credentialFile", d.credentialFile()); row.put("seeded", DeploymentRegistry.SEEDED.contains(d.id()));
            row.put("connected", d.broker() || d.anthropic() || clients.openai(d).available());
            row.put("usedPercent", balancer.usedPercent(d, now));
            row.put("inFlight", h.inFlight()); row.put("firstByteMs", h.ttfbMs() == null ? null : Math.round(h.ttfbMs()));
            row.put("errorRate", Math.round(h.errorRate() * 1000) / 1000.0);
            row.put("cooldownUntil", h.cooldownUntil().isAfter(now) ? h.cooldownUntil().toString() : null);
            row.put("quotaBlockedUntil", h.quotaBlockedUntil().isAfter(now) ? h.quotaBlockedUntil().toString() : null);
            row.put("lastError", h.lastError());
            row.put("sessions", n);
            deployments.add(row);
        }
        result.put("deployments", deployments);
        result.put("memberKeys", jdbc.queryForObject("SELECT COUNT(*) FROM member_broker_keys", Long.class));
        result.put("state", Map.of("sessionsOnClaude", onClaude, "sessionsOnCodex", onCodex, "sessionsOnBroker", onBroker));
        result.put("servedLast24h", jdbc.queryForList("""
            SELECT provider, upstream_account AS "account", COUNT(*) AS requests FROM requests
            WHERE started_at>CURRENT_TIMESTAMP-INTERVAL '1' DAY AND path='/v1/messages' GROUP BY provider, upstream_account ORDER BY 3 DESC
            """));
        return result;
    }

    public record Update(@NotNull Boolean gptModelsEnabled, @NotNull Boolean fallbackEnabled,
                         @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String mapOpus,
                         @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String mapSonnet,
                         @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String mapHaiku,
                         @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal memberWeeklyBudgetUsd,
                         @NotBlank @Pattern(regexp = "balanced|claude_first|latency|least_busy") String strategy,
                         @NotNull @Min(1) @Max(20) Integer allowedFails, @NotNull @Min(5) @Max(3600) Integer cooldownSeconds,
                         @NotNull @Min(0) @Max(3) Integer transientRetries, @NotNull @Min(10) @Max(600) Integer firstByteTimeoutSeconds) {}

    @PutMapping
    public Map<String, Object> update(@Valid @RequestBody Update body, HttpServletRequest request) {
        var before = settings.get();
        String actor = actor(request);
        settings.update(new RoutingSettings.Values(body.gptModelsEnabled(), body.fallbackEnabled(), before.codexEnabled(), before.brokerEnabled(),
                body.mapOpus(), body.mapSonnet(), body.mapHaiku(), body.memberWeeklyBudgetUsd(), body.strategy(),
                body.allowedFails(), body.cooldownSeconds(), body.transientRetries(), body.firstByteTimeoutSeconds(), Instant.now(), actor), actor);
        var details = new LinkedHashMap<String, Object>();
        details.put("previousFallbackEnabled", before.fallbackEnabled()); details.put("fallbackEnabled", body.fallbackEnabled());
        details.put("previousStrategy", before.strategy()); details.put("strategy", body.strategy());
        details.put("gptModelsEnabled", body.gptModelsEnabled());
        details.put("mapOpus", body.mapOpus()); details.put("mapSonnet", body.mapSonnet()); details.put("mapHaiku", body.mapHaiku());
        details.put("memberWeeklyBudgetUsd", body.memberWeeklyBudgetUsd());
        details.put("allowedFails", body.allowedFails()); details.put("cooldownSeconds", body.cooldownSeconds());
        details.put("transientRetries", body.transientRetries()); details.put("firstByteTimeoutSeconds", body.firstByteTimeoutSeconds());
        audit.record(request, "routing.settings.update", "routing", details);
        return status();
    }

    public record DeploymentInput(@NotBlank @Size(max = 100) String name, @NotBlank String kind, @NotBlank String account,
                                  @NotNull Integer priority, @NotNull Integer weight, @NotNull Boolean enabled,
                                  Integer maxConcurrency, Integer contextWindowTokens, String credentialFile) {}

    private Map<String, Object> save(String id, DeploymentInput in, boolean creating, HttpServletRequest request) {
        var d = new Deployment(id, in.name().strip(), in.kind(), in.account(), in.priority(), in.weight(), in.enabled(),
                in.maxConcurrency(), in.contextWindowTokens(), in.credentialFile() == null || in.credentialFile().isBlank() ? null : in.credentialFile().strip());
        try { registry.save(d, creating, actor(request)); }
        catch (DeploymentRegistry.Invalid e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        var details = new LinkedHashMap<String, Object>();
        details.put("kind", d.kind()); details.put("account", d.account()); details.put("priority", d.priority()); details.put("weight", d.weight());
        details.put("enabled", d.enabled()); details.put("maxConcurrency", d.maxConcurrency()); details.put("contextWindowTokens", d.contextWindowTokens());
        details.put("hasExtraCredentials", d.credentialFile() != null);
        audit.record(request, creating ? "routing.deployment.create" : "routing.deployment.update", id, details);
        return status();
    }

    @PostMapping("/deployments/{id}")
    public Map<String, Object> create(@PathVariable String id, @Valid @RequestBody DeploymentInput body, HttpServletRequest request) {
        return save(id, body, true, request);
    }

    @PutMapping("/deployments/{id}")
    public Map<String, Object> edit(@PathVariable String id, @Valid @RequestBody DeploymentInput body, HttpServletRequest request) {
        return save(id, body, false, request);
    }

    @DeleteMapping("/deployments/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpServletRequest request) {
        try { registry.delete(id); }
        catch (DeploymentRegistry.Invalid e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        audit.record(request, "routing.deployment.delete", id, Map.of());
        return status();
    }
}
