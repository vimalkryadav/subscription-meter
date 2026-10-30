package dev.subscriptionmeter.audit;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(10)
public class GatewayAccessFilter extends OncePerRequestFilter {
    public static final String CONTEXT="meter.audit.context", MEMBER="meter.audit.member";
    private final AuditStore store;
    private final CredentialRedactor redact;
    private final MeterRegistry metrics;
    public GatewayAccessFilter(AuditStore store, CredentialRedactor redact, MeterRegistry metrics) {
        this.store=store; this.redact=redact; this.metrics=metrics;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getServletPath().startsWith("/v1/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        long start=System.nanoTime();
        Map<String,List<String>> headers=new TreeMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> headers.put(name,Collections.list(request.getHeaders(name))));
        AuditContext context=new AuditContext(UUID.randomUUID().toString(),Instant.now(),request.getMethod(),
                request.getServletPath(),request.getQueryString(),headers,request.getRemoteAddr(),request.getProtocol());
        request.setAttribute(CONTEXT,context);
        response.setHeader("x-meter-request-id",context.id());
        boolean threw=false;
        try { chain.doFilter(request,response); }
        catch (IOException | ServletException | RuntimeException e) { threw=true; throw e; }
        finally {
            try { store.access(context,(String)request.getAttribute(MEMBER),threw?500:response.getStatus(),
                    (System.nanoTime()-start)/1_000_000,redact.headers(headers),redact.query(context.query())); }
            catch (RuntimeException e) { metrics.counter("meter.audit.failures").increment(); }
        }
    }
}
