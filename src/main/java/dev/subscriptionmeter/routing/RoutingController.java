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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Administrator-only (under /admin): fallback switches, model mapping and live routing status. */
@RestController
@RequestMapping("/admin/routing")
public class RoutingController {
    private final RoutingSettings settings;
    private final RoutingState state;
    private final CodexTarget codex;
    private final QuotaBalancer balancer;
    private final JdbcTemplate jdbc;
    private final AdminActionAudit audit;
    private final SessionService sessions;

    public RoutingController(RoutingSettings settings, RoutingState state, CodexTarget codex, QuotaBalancer balancer, JdbcTemplate jdbc, AdminActionAudit audit, SessionService sessions) {
        this.settings = settings; this.state = state; this.codex = codex; this.balancer = balancer; this.jdbc = jdbc; this.audit = audit; this.sessions = sessions;
    }

    @GetMapping
    public Map<String, Object> status() {
        var s = settings.get();
        var result = new LinkedHashMap<String, Object>();
        result.put("gptModelsEnabled", s.gptModelsEnabled());
        result.put("fallbackEnabled", s.fallbackEnabled());
        result.put("codexEnabled", s.codexEnabled());
        result.put("brokerEnabled", s.brokerEnabled());
        result.put("mapOpus", s.mapOpus()); result.put("mapSonnet", s.mapSonnet()); result.put("mapHaiku", s.mapHaiku());
        result.put("memberWeeklyBudgetUsd", s.memberWeeklyBudgetUsd());
        result.put("strategy", s.strategy());
        var headroom = balancer.headroom(Instant.now());
        var balance = new LinkedHashMap<String, Object>();
        balance.put("claudeUsedPercent", headroom.claudeUsedPercent());
        balance.put("codexUsedPercent", headroom.codexUsedPercent());
        balance.put("newSessionsGoTo", headroom.preferred());
        result.put("balance", balance);
        result.put("updatedAt", s.updatedAt().toString()); result.put("updatedBy", s.updatedBy());
        result.put("codexConfigured", codex.available());
        result.put("memberKeys", jdbc.queryForObject("SELECT COUNT(*) FROM member_broker_keys", Long.class));
        result.put("state", state.status(Instant.now()));
        result.put("servedLast24h", jdbc.queryForList("""
            SELECT provider, upstream_account AS "account", COUNT(*) AS requests FROM requests
            WHERE started_at>CURRENT_TIMESTAMP-INTERVAL '1' DAY AND path='/v1/messages' GROUP BY provider, upstream_account ORDER BY 3 DESC
            """));
        return result;
    }

    public record Update(@NotNull Boolean gptModelsEnabled, @NotNull Boolean fallbackEnabled, @NotNull Boolean codexEnabled, @NotNull Boolean brokerEnabled,
                         @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String mapOpus,
                         @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String mapSonnet,
                         @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String mapHaiku,
                         @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal memberWeeklyBudgetUsd,
                         @NotBlank @Pattern(regexp = "claude_first|balanced") String strategy) {}

    @PutMapping
    public Map<String, Object> update(@Valid @RequestBody Update body, HttpServletRequest request) {
        var before = settings.get();
        String actor = PortalAuthFilter.cookie(request) == null ? "bootstrap" : sessions.actor(request);
        settings.update(new RoutingSettings.Values(body.gptModelsEnabled(), body.fallbackEnabled(), body.codexEnabled(), body.brokerEnabled(),
                body.mapOpus(), body.mapSonnet(), body.mapHaiku(), body.memberWeeklyBudgetUsd(), body.strategy(), Instant.now(), actor), actor);
        var details = new LinkedHashMap<String, Object>();
        details.put("previousFallbackEnabled", before.fallbackEnabled()); details.put("fallbackEnabled", body.fallbackEnabled());
        details.put("previousStrategy", before.strategy()); details.put("strategy", body.strategy());
        details.put("gptModelsEnabled", body.gptModelsEnabled()); details.put("codexEnabled", body.codexEnabled()); details.put("brokerEnabled", body.brokerEnabled());
        details.put("mapOpus", body.mapOpus()); details.put("mapSonnet", body.mapSonnet()); details.put("mapHaiku", body.mapHaiku());
        details.put("memberWeeklyBudgetUsd", body.memberWeeklyBudgetUsd());
        audit.record(request, "routing.settings.update", "routing", details);
        return status();
    }
}
