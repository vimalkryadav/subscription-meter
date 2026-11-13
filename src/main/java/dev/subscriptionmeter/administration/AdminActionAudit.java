package dev.subscriptionmeter.administration;

import dev.subscriptionmeter.accounting.AccountingService;
import dev.subscriptionmeter.portal.PortalAuthFilter;
import dev.subscriptionmeter.portal.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Records a verified actor and bounded metadata; never records credentials or request bodies. */
@Service
public class AdminActionAudit {
    private final SessionService sessions;
    private final AccountingService accounting;
    public AdminActionAudit(SessionService sessions, AccountingService accounting) {
        this.sessions = sessions; this.accounting = accounting;
    }
    public void record(HttpServletRequest request, String action, String target, Map<String, ?> details) {
        String cookie = PortalAuthFilter.cookie(request);
        String actor = cookie == null ? "bootstrap" : sessions.actor(request);
        var safe = new LinkedHashMap<String, Object>(details);
        // A diagnostic client label, not an authentication assertion.
        safe.put("client", "mcp".equals(request.getHeader("X-Control-Usage-Client")) ? "mcp" : "dashboard_or_api");
        accounting.log(actor, action, target, safe);
    }
}
