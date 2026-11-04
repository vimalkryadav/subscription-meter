package dev.subscriptionmeter.web;

import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.security.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final MemberService members;
    private final AnalyticsReader analytics;
    private final String mode, tokenFile;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final dev.subscriptionmeter.audit.AuditStore audit;
    @Value("${meter.audit.retention-days:30}") private int retentionDays;
    @Value("${meter.audit.timescale-enabled:false}") private boolean timescale;
    public AdminController(MemberService members, AnalyticsReader analytics, @Value("${meter.mode}") String mode,
            @Value("${meter.upstream-token-file}") String tokenFile,org.springframework.jdbc.core.JdbcTemplate jdbc,
            dev.subscriptionmeter.audit.AuditStore audit) {
        this.members = members; this.analytics = analytics; this.mode = mode; this.tokenFile = tokenFile;this.jdbc=jdbc;this.audit=audit;
    }
    public record NewMember(@NotBlank @Size(max=100) String name, @Min(1) @Max(600) int rpm,
                            @Min(1) @Max(20) int concurrency) {}
    @PostMapping("/members") @ResponseStatus(HttpStatus.CREATED)
    public MemberService.IssuedKey create(@Valid @RequestBody NewMember body) {
        return members.create(body.name().strip(), body.rpm(), body.concurrency());
    }
    @GetMapping("/members") public List<Member> list() { return members.list(); }
    @DeleteMapping("/members/{id}") public Map<String, String> revoke(@PathVariable String id) {
        members.revoke(id); return Map.of("revoked", id, "note", "New requests blocked; in-flight requests may finish.");
    }
    @GetMapping("/analytics") public Map<String, Object> analytics(@RequestParam(defaultValue="7") int days) {
        if (days < 1 || days > 90) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return Map.of("mode", mode, "rows", analytics.summarize(Instant.now().minus(days, ChronoUnit.DAYS)),
                "note", "Observed tokens; not subscription allowance or a bill. Partial usage may be incomplete.");
    }
    @GetMapping("/status") public Map<String, Object> status() {
        boolean configured = false;
        try { configured = Files.isRegularFile(Path.of(tokenFile)) && Files.size(Path.of(tokenFile))>0; }
        catch (java.io.IOException ignored) { }
        var successful=jdbc.queryForList("SELECT started_at,model FROM requests WHERE provider='anthropic' AND path='/v1/messages' AND state='complete' AND status_code=200 ORDER BY started_at DESC LIMIT 1");
        var latest=jdbc.queryForList("SELECT started_at,state,status_code FROM requests WHERE provider='anthropic' AND path='/v1/messages' ORDER BY started_at DESC LIMIT 1");
        var status=new LinkedHashMap<String,Object>();
        status.put("mode",mode);status.put("credentialConfigured",configured);
        status.put("subscriptionCompatibility",successful.isEmpty()?"unverified":"generation observed");
        status.put("lastSuccessfulGeneration",successful.isEmpty()?null:successful.getFirst());
        status.put("latestGeneration",latest.isEmpty()?null:latest.getFirst());
        status.put("runtime",Runtime.version().toString());status.put("limits","shared PostgreSQL admission");
        status.put("thread",Thread.currentThread().isVirtual()?"virtual":"platform");
        status.put("fullCapture",false);status.put("capturePolicy",audit.requiresAcknowledgedForwarding()?"bounded redacted capture; broker acknowledgement before forwarding":"bounded redacted capture; best effort after admission");
        status.put("auditReady",audit.ready());status.put("detailRetentionDays",retentionDays);status.put("timescaleEnabled",timescale);
        return status;
    }
}
