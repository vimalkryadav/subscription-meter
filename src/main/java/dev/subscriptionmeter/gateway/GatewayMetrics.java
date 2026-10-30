package dev.subscriptionmeter.gateway;

import dev.subscriptionmeter.domain.RequestResult;
import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class GatewayMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger active = new AtomicInteger();
    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry; Gauge.builder("meter.active.requests", active, AtomicInteger::get).register(registry);
    }
    public void started() { active.incrementAndGet(); }
    public void finished(RequestResult r) {
        active.decrementAndGet();
        registry.counter("meter.requests", "state", r.state(), "status", Integer.toString(r.status())).increment();
        registry.timer("meter.request.duration").record(Duration.ofMillis(r.durationMs()));
        var u = r.usage();
        registry.counter("meter.tokens", "type", "input").increment(u.input());
        registry.counter("meter.tokens", "type", "output").increment(u.output());
        registry.counter("meter.tokens", "type", "cache_creation").increment(u.cacheCreation());
        registry.counter("meter.tokens", "type", "cache_read").increment(u.cacheRead());
    }
    public void persistenceFailed() { registry.counter("meter.persistence.failures").increment(); }
}
