package dev.subscriptionmeter.portal;

import dev.subscriptionmeter.security.MemberService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/portal/api")
public class PortalController {
    private final AccountService accounts;
    private final SessionService sessions;
    private final PortalAccessService access;
    private final boolean secure;
    private final String mode;
    public PortalController(AccountService accounts,SessionService sessions,PortalAccessService access,
            @Value("${meter.portal.secure-cookie:true}") boolean secure,@Value("${meter.mode}") String mode) {
        this.accounts=accounts;this.sessions=sessions;this.access=access;this.secure=secure;this.mode=mode;
    }
    public record Register(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.-]{2,39}") String username,
                           @NotBlank @Size(max=55) String displayName,@Size(min=12,max=128) @NotNull String password) {}
    public record Login(@NotBlank @Size(max=40) String username,@NotNull @Size(min=1,max=128) String password) {}
    public record AccessRequest(@NotBlank @Size(max=500) String reason) {}
    private String accountId(HttpServletRequest req) { return (String)req.getAttribute(PortalAuthFilter.ACCOUNT); }
    private void setCookie(HttpServletResponse res,String value,long seconds) {
        res.addHeader("Set-Cookie",ResponseCookie.from(PortalAuthFilter.COOKIE,value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/").maxAge(seconds).build().toString());
        // Retire the previous cookie path so admin pages use the same account session.
        res.addHeader("Set-Cookie",ResponseCookie.from(PortalAuthFilter.COOKIE,"").httpOnly(true).secure(secure)
                .sameSite("Strict").path("/portal/api").maxAge(0).build().toString());
    }
    private void refreshCookie(HttpServletResponse res,String raw,long age){
        res.addHeader("Set-Cookie",ResponseCookie.from(PortalAuthFilter.REFRESH,raw).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/portal/api").maxAge(age).build().toString());
    }
    private Map<String,Object> tokenResponse(SessionService.Tokens tokens,HttpServletResponse res){
        setCookie(res,tokens.access(),900);refreshCookie(res,tokens.refresh(),400L*86400);
        return Map.of("user",accounts.find(tokens.accountId()).profile(),"csrfToken",tokens.csrf(),"accessExpiresIn",900);
    }
    @PostMapping("/refresh") public Map<String,Object> refresh(HttpServletRequest req,HttpServletResponse res){
        return tokenResponse(sessions.renew(PortalAuthFilter.refreshCookie(req)),res);
    }
    private Map<String,Object> signIn(Account account,HttpServletRequest req,HttpServletResponse res) {
        if("1".equals(req.getHeader("X-Portal-Persistent")))
            return tokenResponse(sessions.createPersistent(account.id(),PortalAuthFilter.cookie(req),PortalAuthFilter.refreshCookie(req)),res);
        String raw=sessions.createLegacy(account.id(),PortalAuthFilter.cookie(req),PortalAuthFilter.refreshCookie(req));
        setCookie(res,raw,43200);refreshCookie(res,"",0);
        return Map.of("user",account.profile(),"csrfToken",PortalTokens.csrf(raw));
    }
    @PostMapping(value="/register",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(@Valid @RequestBody Register body,HttpServletRequest req,HttpServletResponse res) {
        return signIn(accounts.register(body.username(),body.displayName(),body.password()),req,res);
    }
    @PostMapping(value="/login",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> login(@Valid @RequestBody Login body,HttpServletRequest req,HttpServletResponse res) {
        return signIn(accounts.login(body.username(),body.password()),req,res);
    }
    @GetMapping("/session") public Map<String,Object> session(HttpServletRequest req,HttpServletResponse res) {
        if(!sessions.persistent(PortalAuthFilter.cookie(req)))setCookie(res,PortalAuthFilter.cookie(req),43200);
        return Map.of("user",accounts.find(accountId(req)).profile(),"csrfToken",sessions.csrf(PortalAuthFilter.cookie(req)));
    }
    @PostMapping("/logout") public Map<String,Boolean> logout(HttpServletRequest req,HttpServletResponse res) {
        sessions.logout(PortalAuthFilter.cookie(req),PortalAuthFilter.refreshCookie(req));setCookie(res,"",0);refreshCookie(res,"",0);return Map.of("signedOut",true);
    }
    @GetMapping("/access") public Map<String,Object> access(HttpServletRequest req) {
        return Map.of("access",access.status(accountId(req)),"mode",mode);
    }
    @PostMapping(value="/key-requests",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> request(@Valid @RequestBody AccessRequest body,HttpServletRequest req) {
        access.request(accountId(req),body.reason());return access(req);
    }
    @PostMapping("/token") public MemberService.IssuedKey token(HttpServletRequest req) { return access.issue(accountId(req),false); }
    @PostMapping("/token/rotate") public MemberService.IssuedKey rotate(HttpServletRequest req) { return access.issue(accountId(req),true); }
}
