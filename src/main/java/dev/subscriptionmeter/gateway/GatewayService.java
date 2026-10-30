package dev.subscriptionmeter.gateway;

import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.limits.RequestLimiter;
import dev.subscriptionmeter.upstream.UpstreamTransport;
import dev.subscriptionmeter.usage.AnthropicUsageObserver;
import java.io.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
    public GatewayService(RequestLimiter limiter, UpstreamTransport transport, RequestLedger ledger, GatewayMetrics metrics) {
        this.limiter = limiter; this.transport = transport; this.ledger = ledger; this.metrics = metrics;
    }
    @EventListener(ApplicationReadyEvent.class)
    void recover() { ledger.markInterrupted(); }

    public void forward(Member member, String method, String path, String query, String model,
                        Map<String, String> headers, byte[] body, ResponseSink sink) {
        try (var permit = limiter.acquire(member)) {
            String id = UUID.randomUUID().toString();
            ledger.begin(id, member.id(), path, model);
            long start = System.nanoTime();
            metrics.started();
            String state = "upstream_error", upstreamId = "";
            int status = 502;
            boolean responseStarted = false;
            AnthropicUsageObserver observer = null;
            try (var reply = transport.send(method, path + (query == null ? "" : "?" + query), headers, body)) {
                status = reply.status();
                Map<String, String> outgoing = new HashMap<>();
                reply.headers().forEach((name, values) -> {
                    String key = name.toLowerCase(Locale.ROOT);
                    if (Set.of("content-type", "request-id", "retry-after").contains(key) || key.startsWith("anthropic-ratelimit-"))
                        outgoing.put(key, String.join(",", values));
                });
                upstreamId = outgoing.getOrDefault("request-id", "");
                upstreamId = upstreamId.substring(0, Math.min(200, upstreamId.length()));
                boolean streaming = outgoing.getOrDefault("content-type", "").contains("text/event-stream");
                observer = new AnthropicUsageObserver(streaming);
                outgoing.put("x-meter-request-id", id);
                outgoing.put("cache-control", "no-store"); outgoing.put("x-accel-buffering", "no");
                sink.start(status, outgoing); responseStarted = true;
                state = status >= 400 ? "upstream_error" : "partial";
                byte[] buffer = new byte[8192];
                int count;
                long totalBytes = 0;
                while ((count = reply.body().read(buffer)) != -1) {
                    if (!streaming && (totalBytes += count) > 32 * 1024 * 1024) throw new IOException("Response size limit");
                    observer.accept(buffer, count);
                    try { sink.write(buffer, count); }
                    catch (IOException disconnected) { state = "disconnected"; return; }
                }
                if (!streaming) observer.finishJson();
                if (status < 400) state = observer.error() ? "stream_error" :
                        !path.equals("/v1/messages") || observer.complete() ? "complete" : "partial";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); state = "interrupted";
                if (!responseStarted) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream interrupted");
            } catch (IOException e) {
                state = responseStarted ? "partial" : "upstream_error";
                if (!responseStarted) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream unavailable");
            } finally {
                RequestResult result = new RequestResult(state, status, (System.nanoTime() - start) / 1_000_000,
                        upstreamId, observer == null ? Usage.empty() : observer.snapshot());
                metrics.finished(result);
                try { ledger.finish(id, result); }
                catch (RuntimeException e) {
                    metrics.persistenceFailed();
                    LOG.error("Usage write failed for request {}; pending record needs reconciliation", id);
                }
            }
        }
    }
}
