package dev.subscriptionmeter.summaries;

import dev.subscriptionmeter.administration.AdminActionAudit;
import dev.subscriptionmeter.portal.PortalAuthFilter;
import dev.subscriptionmeter.portal.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Administrator-only (all /admin/** routes pass the admin session filter). */
@RestController
@RequestMapping("/admin")
public class SessionSummaryController {
    private final JdbcTemplate jdbc;
    private final OpenAIApiBudget budget;
    private final OpenAIApiKey key;
    private final AdminActionAudit audit;
    private final SessionService sessions;

    public SessionSummaryController(JdbcTemplate jdbc, OpenAIApiBudget budget, OpenAIApiKey key, AdminActionAudit audit, SessionService sessions) {
        this.jdbc = jdbc; this.budget = budget; this.key = key; this.audit = audit; this.sessions = sessions;
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(@RequestParam(defaultValue = "7") int days, @RequestParam(defaultValue = "") String memberId) {
        if (days < 1 || days > 30 || memberId.length() > 36) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return jdbc.queryForList("""
            SELECT s.member_id AS "memberId",m.name AS "memberName",s.session_id AS "sessionId",s.first_request_at AS "firstRequestAt",
            s.last_request_at AS "lastRequestAt",s.request_count AS "requestCount",s.status,s.summary,s.category,s.model,
            s.cost_usd AS "costUsd",s.last_error AS "lastError",s.summarized_through AS "summarizedThrough",s.updated_at AS "updatedAt"
            FROM session_summaries s LEFT JOIN members m ON m.id=s.member_id
            WHERE s.last_request_at>=? AND (?='' OR s.member_id=?) ORDER BY s.last_request_at DESC LIMIT 500
            """, Timestamp.from(Instant.now().minus(Duration.ofDays(days))), memberId, memberId);
    }

    @GetMapping("/openai-api")
    public Map<String, Object> status() { return budget.status(Instant.now(), key.configured()); }

    public record Settings(@NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal weeklyCapUsd,
                           @NotNull Boolean summariesEnabled,
                           @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") String summaryModel,
                           @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal inputUsdPerMTok,
                           @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal cachedInputUsdPerMTok,
                           @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal outputUsdPerMTok) {}

    @PutMapping("/openai-api")
    public Map<String, Object> update(@Valid @RequestBody Settings body, HttpServletRequest request) {
        var before = budget.settings();
        String actorLabel = PortalAuthFilter.cookie(request) == null ? "bootstrap" : sessions.actor(request);
        budget.update(body.weeklyCapUsd(), body.summariesEnabled(), body.summaryModel(), body.inputUsdPerMTok(),
                body.cachedInputUsdPerMTok(), body.outputUsdPerMTok(), actorLabel);
        var details = new LinkedHashMap<String, Object>();
        details.put("previousWeeklyCapUsd", before.weeklyCapUsd()); details.put("weeklyCapUsd", body.weeklyCapUsd());
        details.put("previousSummariesEnabled", before.summariesEnabled()); details.put("summariesEnabled", body.summariesEnabled());
        details.put("previousSummaryModel", before.summaryModel()); details.put("summaryModel", body.summaryModel());
        details.put("inputUsdPerMTok", body.inputUsdPerMTok()); details.put("cachedInputUsdPerMTok", body.cachedInputUsdPerMTok());
        details.put("outputUsdPerMTok", body.outputUsdPerMTok());
        audit.record(request, "openai_api.settings.update", "openai_api", details);
        return status();
    }
}
