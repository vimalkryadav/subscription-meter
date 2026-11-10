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
    private final dev.subscriptionmeter.deployment.DeploymentState deployment;
    public GatewayAccessFilter(AuditStore store, CredentialRedactor redact, MeterRegistry metrics) {
        this(store,redact,metrics,new dev.subscriptionmeter.deployment.DeploymentState());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public GatewayAccessFilter(AuditStore store,CredentialRedactor redact,MeterRegistry metrics,dev.subscriptionmeter.deployment.DeploymentState deployment){
        this.store=store;this.redact=redact;this.metrics=metrics;this.deployment=deployment;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getServletPath().startsWith("/v1/")&&!request.getServletPath().startsWith("/openai/v1/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try(var tracked=deployment.trackAudit()){
        long start=System.nanoTime();
        Map<String,List<String>> headers=new TreeMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> headers.put(name,Collections.list(request.getHeaders(name))));
        AuditContext context=new AuditContext(UUID.randomUUID().toString(),Instant.now(),request.getMethod(),
                request.getServletPath(),request.getQueryString(),headers,request.getRemoteAddr(),request.getProtocol());
        request.setAttribute(CONTEXT,context);
        response.setHeader("x-meter-request-id",context.id());
        if(store.requiresAcknowledgedForwarding()&&!store.ready()) {
            metrics.counter("meter.audit.admission.rejected").increment();
            response.setStatus(503);response.setHeader("Retry-After","10");response.setHeader("Cache-Control","no-store");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"type\":\"api_error\",\"message\":\"Audit logging temporarily unavailable. Retry later.\"}}");
            return; // Storage is unavailable: count rejection in metrics, never fabricate a persisted audit.
        }
        boolean threw=false;
        try { chain.doFilter(request,response); }
        catch (IOException | ServletException | RuntimeException e) { threw=true; throw e; }
        finally {
            try { store.access(context,(String)request.getAttribute(MEMBER),threw?500:response.getStatus(),
                    (System.nanoTime()-start)/1_000_000,redact.headers(headers),redact.query(context.query())); }
            catch (RuntimeException e) { metrics.counter("meter.audit.failures").increment();deployment.persistenceFailed(); }
        }
        }
    }
}
