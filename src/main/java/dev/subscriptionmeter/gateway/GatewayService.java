package dev.subscriptionmeter.gateway;

import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.audit.*;
import dev.subscriptionmeter.limits.RequestLimiter;
import dev.subscriptionmeter.upstream.UpstreamTransport;
import dev.subscriptionmeter.usage.UsageObserver;
import dev.subscriptionmeter.provider.GatewayProtocols;
import java.io.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.subscriptionmeter.deployment.*;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GatewayService {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayService.class);
    private final RequestLimiter limiter;
    private final UpstreamTransport transport;
    private final RequestLedger ledger;
    private final GatewayMetrics metrics;
    private final AuditCaptureFactory audits;
    private final DeploymentState deployment;
    private final InstanceLease lease;
    public GatewayService(RequestLimiter limiter, UpstreamTransport transport, RequestLedger ledger, GatewayMetrics metrics, AuditCaptureFactory audits) {
        this(limiter,transport,ledger,metrics,audits,new DeploymentState(),null);
    }
    @Autowired public GatewayService(RequestLimiter limiter,UpstreamTransport transport,RequestLedger ledger,
            GatewayMetrics metrics,AuditCaptureFactory audits,DeploymentState deployment,InstanceLease lease) {
        this.limiter=limiter; this.transport=transport; this.ledger=ledger; this.metrics=metrics;
        this.audits=audits; this.deployment=deployment; this.lease=lease;
    }
    private void requireLease() { if(lease!=null) lease.requireHealthy(); }

    public void forward(Member member, AuditContext context, String model,
                        Map<String, String> headers, byte[] body, ResponseSink sink) {
        long totalStarted=System.nanoTime();
        var delivery=new ResponseDelivery(sink,metrics,totalStarted);
        try (var tracked = deployment.enter(); var permit = acquire(member)) {
            requireLease();
            audits.requireReady();
            String id = context.id(), method=context.method(), path=context.path(), query=context.query();
            var protocol=GatewayProtocols.forPath(path);
            long start = System.nanoTime();
            ledger.begin(id, member.id(), path, model,context.startedAt());
            String userAgent=headers.getOrDefault("user-agent","").toLowerCase(Locale.ROOT);
            String client=protocol.id().equals("openai")?"codex":userAgent.contains("claude-code")||userAgent.contains("claude-cli")?"claude-code":"unknown";
            metrics.started();
            String state = "upstream_error", upstreamId = "";
            int status = 502;
            UsageObserver observer = null;
            AuditCapture capture = null;
            try {
              ledger.identify(id,protocol.id(),"primary",client);
              long auditStarted=System.nanoTime();
              try { capture=audits.begin(context,member.id(),body); }
              finally { metrics.phase(GatewayMetrics.Phase.REQUEST_AUDIT,auditStarted); }
              requireLease();
              try (var reply = send(method, path + (query == null ? "" : "?" + query), headers, body)) {
                status = reply.status();
                Map<String, String> outgoing = new HashMap<>();
                reply.headers().forEach((name, values) -> {
                    String key = name.toLowerCase(Locale.ROOT);
                    if (protocol.responseHeader(key))
                        outgoing.put(key, String.join(",", values));
                });
                upstreamId = protocol.requestId(outgoing);
                upstreamId = upstreamId.substring(0, Math.min(200, upstreamId.length()));
                boolean streaming = outgoing.getOrDefault("content-type", "").contains("text/event-stream");
                capture.headers(reply.headers(),streaming);
                observer = protocol.observer(streaming);
                outgoing.put("x-meter-request-id", id);
                outgoing.put("cache-control", "no-store"); outgoing.put("x-accel-buffering", "no");
                delivery.start(status, outgoing);
                state = status >= 400 ? "upstream_error" : "partial";
                byte[] buffer = new byte[8192];
                int count;
                long totalBytes = 0;
                while ((count = reply.body().read(buffer)) != -1) {
                    if (!streaming && (totalBytes += count) > 32 * 1024 * 1024) throw new IOException("Response size limit");
                    observer.accept(buffer, count);
                    requireLease();
                    long forwardingStarted=System.nanoTime();
                    try { capture.forward(buffer,count,delivery::write); }
                    finally { metrics.phase(GatewayMetrics.Phase.FORWARDING,forwardingStarted); }
                }
                long forwardingStarted=System.nanoTime();
                try { capture.completeForwarding(delivery::write); }
                finally { metrics.phase(GatewayMetrics.Phase.FORWARDING,forwardingStarted); }
                if (!streaming) observer.finishJson();
                if (status < 400) state = observer.error() ? "stream_error" :
                        !protocol.inference(path) || observer.complete() ? "complete" : "partial";
              }
            } catch (ResponseDelivery.DownstreamFailure e) {
                if(capture!=null)capture.error(e);state="disconnected";
                if(!delivery.committed()){status=499;delivery.prepareError(status);}
            } catch (InterruptedException e) {
                if (capture != null) capture.error(e);
                Thread.currentThread().interrupt(); state = "interrupted";
                if (!delivery.committed()) { status=502;delivery.prepareError(status);throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream interrupted"); }
            } catch (IOException e) {
                if (capture != null) capture.error(e);
                state = delivery.committed() ? "partial" : "upstream_error";
                if (!delivery.committed()) { status=502;delivery.prepareError(status);throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream unavailable"); }
            } catch (RuntimeException e) {
                state="gateway_error";
                if(!delivery.committed()){status=e instanceof ResponseStatusException unavailable?unavailable.getStatusCode().value():500;delivery.prepareError(status);}
                if (capture != null) capture.error(e);
                throw e;
            } finally {
                long finalizeStarted=System.nanoTime();
                try {
                    if (capture != null) capture.finish(state);
                } catch(RuntimeException auditFailure) {
                    if(!delivery.committed()){
                        status=auditFailure instanceof ResponseStatusException unavailable?unavailable.getStatusCode().value():500;
                        state="gateway_error";delivery.prepareError(status);
                    }
                    metrics.persistenceFailed();
                    deployment.persistenceFailed();
                    LOG.error("Final audit write failed for request {}; reconciliation required",id);
                    throw auditFailure;
                } finally {
                metrics.phase(GatewayMetrics.Phase.CAPTURE_FINALIZATION,finalizeStarted);
                RequestResult result = new RequestResult(state, status, (System.nanoTime() - start) / 1_000_000,
                        upstreamId, observer == null ? Usage.empty() : observer.snapshot());
                long resultStarted=System.nanoTime();
                try { ledger.finish(id, result); }
                catch (RuntimeException e) {
                    metrics.persistenceFailed();
                    deployment.persistenceFailed();
                    LOG.error("Usage write failed for request {}; pending record needs reconciliation", id);
                } finally { metrics.phase(GatewayMetrics.Phase.RESULT_AUDIT,resultStarted);metrics.finished(result); }
                }
            }
        } finally { metrics.phase(GatewayMetrics.Phase.TOTAL_GATEWAY,totalStarted); }
    }
    private RequestLimiter.Permit acquire(Member member) {
        long started=System.nanoTime();
        try { return limiter.acquire(member); }
        finally { metrics.phase(GatewayMetrics.Phase.ADMISSION,started); }
    }
    private UpstreamTransport.Reply send(String method,String path,Map<String,String> headers,byte[] body) throws IOException,InterruptedException {
        long started=System.nanoTime();
        try { return transport.send(method,path,headers,body); }
        finally { metrics.phase(GatewayMetrics.Phase.UPSTREAM_HEADERS,started); }
    }
}
