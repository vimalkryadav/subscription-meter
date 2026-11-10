package dev.subscriptionmeter.gateway;

import dev.subscriptionmeter.domain.RequestResult;
import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class GatewayMetrics {
    public enum Phase { ADMISSION, REQUEST_AUDIT, UPSTREAM_HEADERS, FIRST_FORWARD, FORWARDING, DOWNSTREAM_WRITE, CAPTURE_FINALIZATION, RESULT_AUDIT, ACCESS_AUDIT, TOTAL_GATEWAY }
    private static final Duration[] BOUNDS = java.util.stream.LongStream.of(1,5,10,25,50,100,250,500,1000,5000,30000,300000).mapToObj(Duration::ofMillis).toArray(Duration[]::new);
    private final EnumMap<Phase,Timer> phases = new EnumMap<>(Phase.class);
    public static Timer phaseTimer(MeterRegistry registry,Phase phase) {
        return Timer.builder("meter.gateway.phase").tag("phase",phase.name().toLowerCase(Locale.ROOT))
                .serviceLevelObjectives(BOUNDS).register(registry);
    }
    public void phase(Phase phase,long started) { phases.get(phase).record(System.nanoTime()-started,TimeUnit.NANOSECONDS); }
    private final MeterRegistry registry;
    private final AtomicInteger active = new AtomicInteger();
    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
        for(Phase phase:Phase.values())phases.put(phase,phaseTimer(registry,phase));
        Gauge.builder("meter.active.requests", active, AtomicInteger::get).register(registry);
    }
    public void started() { active.incrementAndGet(); }
    public void finished(RequestResult r) {
        active.decrementAndGet();
        registry.counter("meter.requests", "state", r.state(), "status", Integer.toString(r.status())).increment();
        Timer.builder("meter.request.duration").serviceLevelObjectives(BOUNDS).register(registry).record(Duration.ofMillis(r.durationMs()));
        var u = r.usage();
        registry.counter("meter.tokens", "type", "input").increment(u.input());
        registry.counter("meter.tokens", "type", "output").increment(u.output());
        registry.counter("meter.tokens", "type", "cache_creation").increment(u.cacheCreation());
        registry.counter("meter.tokens", "type", "cache_read").increment(u.cacheRead());
    }
    public void persistenceFailed() { registry.counter("meter.persistence.failures").increment(); }
}
