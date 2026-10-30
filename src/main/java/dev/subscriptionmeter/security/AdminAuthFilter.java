package dev.subscriptionmeter.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminAuthFilter extends OncePerRequestFilter {
    private final String admin;
    private final String metrics;
    public AdminAuthFilter(@Value("${meter.admin-token-file}") String adminFile,
                           @Value("${meter.metrics-token-file}") String metricsFile) throws IOException {
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
        String expected = path.startsWith("/admin/") ? admin :
                path.equals("/actuator/prometheus") ? metrics : null;
        if (expected != null && !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                credential(request).getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401); response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication required\"}"); return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }
}
