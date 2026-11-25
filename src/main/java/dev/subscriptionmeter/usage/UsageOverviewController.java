package dev.subscriptionmeter.usage;

import dev.subscriptionmeter.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class UsageOverviewController {
    private final UsageOverview usage;

    public UsageOverviewController(UsageOverview usage) { this.usage = usage; }

    @GetMapping("/portal/api/usage-overview")
    public UsageOverview.Result portal(HttpServletRequest request, @RequestParam(name = "days", required = false) String days) {
        return usage.forAccount(accountId(request), parseDays(days));
    }

    @GetMapping("/admin/usage-overview")
    public UsageOverview.Result admin(HttpServletRequest request, @RequestParam(name = "days", required = false) String days) {
        // AdminAuthFilter sets this only for a verified administrator session, not the bootstrap token.
        accountId(request);
        return usage.forAdmin(parseDays(days));
    }

    private static String accountId(HttpServletRequest request) {
        if (request.getAttribute(PortalAuthFilter.ACCOUNT) instanceof String account) return account;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
    }

    private static int parseDays(String days) {
        if (days == null || days.equals("7")) return 7;
        if (days.equals("30")) return 30;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 7 or 30");
    }
}
