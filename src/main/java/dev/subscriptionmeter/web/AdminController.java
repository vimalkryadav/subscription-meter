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
    public AdminController(MemberService members, AnalyticsReader analytics, @Value("${meter.mode}") String mode,
            @Value("${meter.upstream-token-file}") String tokenFile) {
        this.members = members; this.analytics = analytics; this.mode = mode; this.tokenFile = tokenFile;
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
        boolean configured = mode.equals("mock");
        try { configured |= Files.isRegularFile(Path.of(tokenFile)) && !Files.readString(Path.of(tokenFile)).isBlank(); }
        catch (java.io.IOException ignored) { }
        return Map.of("mode", mode, "credentialConfigured", configured,
                "subscriptionCompatibility", "unverified", "runtime", Runtime.version().toString(),
                "limits", "single instance", "thread", Thread.currentThread().isVirtual() ? "virtual" : "platform");
    }
}
