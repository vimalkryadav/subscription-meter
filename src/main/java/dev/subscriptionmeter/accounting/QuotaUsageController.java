package dev.subscriptionmeter.accounting;

import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin/quota-usage")
public class QuotaUsageController {
    private final QuotaUsageService usage;
    public QuotaUsageController(QuotaUsageService usage) { this.usage = usage; }

    @GetMapping
    public QuotaUsageService.Report report(@RequestParam(defaultValue = "") String provider) {
        if (!Set.of("", "anthropic", "openai").contains(provider))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid provider");
        return usage.report(Instant.now(), provider);
    }
}
