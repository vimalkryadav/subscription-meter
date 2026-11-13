package dev.subscriptionmeter.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import dev.subscriptionmeter.portal.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AdminAuthFilter extends OncePerRequestFilter {
    private final String admin;
    private final String metrics;
    private final AccountRepository accounts;
    private final SessionService sessions;
    public AdminAuthFilter(@Value("${meter.admin-token-file}") String adminFile,
                           @Value("${meter.metrics-token-file}") String metricsFile,
                           AccountRepository accounts,SessionService sessions) throws IOException {
        this.accounts=accounts;this.sessions=sessions;
        admin = Files.readString(Path.of(adminFile)).strip();
        metrics = Files.readString(Path.of(metricsFile)).strip();
        if (admin.length() < 32 || metrics.length() < 32) throw new IllegalArgumentException("Secrets must be at least 32 characters");
    }
    public static String credential(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) return auth.substring(7);
        String key = request.getHeader("x-api-key");
        return key == null ? "" : key;
    }
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getServletPath();
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        if(path.startsWith("/admin/")) {
            String raw=PortalAuthFilter.cookie(request);
            if(raw!=null) {
                String accountId;
                try { accountId=sessions.authenticate(raw); }
                catch(ResponseStatusException e) { reject(response,401);return; }
                if(!accounts.byId(accountId).map(a->a.role().equals("admin")).orElse(false)) { reject(response,403);return; }
                boolean write=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
                if(write && (!"1".equals(request.getHeader("X-Portal-Request")) ||
                        "cross-site".equals(request.getHeader("Sec-Fetch-Site")) ||
                        !PortalTokens.equal(sessions.csrf(raw),request.getHeader("X-CSRF-Token")))) { reject(response,403);return; }
                request.setAttribute(PortalAuthFilter.ACCOUNT,accountId);
                chain.doFilter(request,response);return;
            }
            // Bootstrap access ends as soon as the first account is promoted.
            if(accounts.hasAdmin() || !PortalTokens.equal(admin,credential(request))) { reject(response,401);return; }
        }
        String expected = path.equals("/actuator/prometheus") ? metrics : null;
        if (expected != null && !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                credential(request).getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401); response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication required\"}"); return;
        }
        chain.doFilter(request, response);
    }
    private void reject(HttpServletResponse response,int code) throws IOException {
        response.setStatus(code);response.setContentType("application/json");
        response.getWriter().write("{\"error\":\""+(code==403?"Administrator access or request verification required":"Sign in required")+"\"}");
    }
}
