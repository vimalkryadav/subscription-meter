package dev.subscriptionmeter.portal;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@Order(10)
public class PortalAuthFilter extends OncePerRequestFilter {
    public static final String COOKIE="meter_session", ACCOUNT="portal.account";
    private final SessionService sessions;
    public PortalAuthFilter(SessionService sessions) { this.sessions=sessions; }
    public static String cookie(HttpServletRequest req) {
        if(req.getCookies()==null) return null;
        return Arrays.stream(req.getCookies()).filter(c->c.getName().equals(COOKIE)).map(Cookie::getValue).findFirst().orElse(null);
    }
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        String path=req.getServletPath();
        if(!path.startsWith("/portal/api/")) { chain.doFilter(req,res); return; }
        res.setHeader("Cache-Control","no-store");
        res.setHeader("X-Content-Type-Options","nosniff");
        boolean write=!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod());
        // Custom headers require CORS preflight; no cross-origin access is enabled.
        if(write && (!"1".equals(req.getHeader("X-Portal-Request")) ||
                "cross-site".equals(req.getHeader("Sec-Fetch-Site")))) { reject(res,403); return; }
        boolean publicAuth=Set.of("/portal/api/register","/portal/api/login").contains(path);
        if(!publicAuth) {
            String raw=cookie(req);
            try { req.setAttribute(ACCOUNT,sessions.authenticate(raw)); }
            catch(ResponseStatusException e) { reject(res,401); return; }
            if(write && !PortalTokens.equal(PortalTokens.csrf(raw),req.getHeader("X-CSRF-Token"))) { reject(res,403); return; }
        }
        chain.doFilter(req,res);
    }
    private void reject(HttpServletResponse res,int status) throws IOException {
        res.setStatus(status);res.setContentType("application/json");
        res.getWriter().write("{\"error\":\""+(status==401?"Sign in required":"Request verification failed")+"\"}");
    }
}
