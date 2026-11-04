package dev.subscriptionmeter.web;

import dev.subscriptionmeter.portal.AccountRepository;
import dev.subscriptionmeter.portal.PortalAuthFilter;
import dev.subscriptionmeter.portal.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** The dashboard document is private too, not just the APIs it calls. */
@RestController
public class AdminPageController {
    private final SessionService sessions;
    private final AccountRepository accounts;
    public AdminPageController(SessionService sessions,AccountRepository accounts) {
        this.sessions=sessions;this.accounts=accounts;
    }
    @GetMapping({"/manage","/manage/"})
    public ResponseEntity<Resource> page(HttpServletRequest request) {
        String accountId;
        try { accountId=sessions.authenticate(PortalAuthFilter.cookie(request)); }
        catch (ResponseStatusException expired) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/"))
                    .cacheControl(CacheControl.noStore()).build();
        }
        if (!accounts.byId(accountId).map(account->account.role().equals("admin")).orElse(false))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("admin/manage.html"));
    }
}
