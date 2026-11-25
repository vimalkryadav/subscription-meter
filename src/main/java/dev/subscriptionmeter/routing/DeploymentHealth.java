package dev.subscriptionmeter.routing;

import io.micrometer.core.instrument.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Live health of each deployment on this instance: requests in flight, time to first byte and error
 * rate (exponentially weighted), cooldowns after repeated transient failures, and quota blocks until
 * the provider's reported reset. Also publishes per-deployment Prometheus metrics.
 */
@Component
public class DeploymentHealth {
    static final double ALPHA = 0.2;
    private final MeterRegistry registry;
    private final Map<String, Stats> stats = new ConcurrentHashMap<>();

    public DeploymentHealth(MeterRegistry registry) { this.registry = registry; }

    static final class Stats {
        final AtomicInteger inFlight = new AtomicInteger();
        volatile double ttfbMs = -1, errorRate;
        volatile Instant cooldownUntil = Instant.EPOCH, quotaBlockedUntil = Instant.EPOCH;
        final Deque<Instant> recentFailures = new ArrayDeque<>();
        volatile String lastError;
    }

    /** Read-only view used by the routing policy and the admin page. */
    public record View(int inFlight, Double ttfbMs, double errorRate, Instant cooldownUntil, Instant quotaBlockedUntil, String lastError) {
        public boolean usable(Instant now) { return !cooldownUntil.isAfter(now) && !quotaBlockedUntil.isAfter(now); }
    }

    private Stats of(String id) {
        return stats.computeIfAbsent(id, k -> {
            var s = new Stats();
            if (registry != null) Gauge.builder("meter.routing.inflight", s.inFlight, AtomicInteger::get).tag("deployment", k).register(registry);
            return s;
        });
    }

    public View view(String id) {
        var s = of(id);
        return new View(s.inFlight.get(), s.ttfbMs < 0 ? null : s.ttfbMs, s.errorRate, s.cooldownUntil, s.quotaBlockedUntil, s.lastError);
    }

    private void count(String id, String outcome) {
        if (registry != null) Counter.builder("meter.routing.attempts").tag("deployment", id).tag("outcome", outcome).register(registry).increment();
    }

    /** Marks an uncapped request in flight. */
    public AutoCloseable acquire(String id) { return tryAcquire(id, null); }

    /** Atomically reserves an in-flight slot, or returns null when the instance-local cap is full. */
    AutoCloseable tryAcquire(String id, Integer cap) {
        var s = of(id);
        for (;;) {
            int active = s.inFlight.get();
            if (cap != null && active >= cap) return null;
            if (s.inFlight.compareAndSet(active, active + 1)) break;
        }
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> { if (released.compareAndSet(false, true)) s.inFlight.decrementAndGet(); };
    }

    public void success(String id, long ttfbMs) {
        var s = of(id);
        s.ttfbMs = s.ttfbMs < 0 ? ttfbMs : s.ttfbMs * (1 - ALPHA) + ttfbMs * ALPHA;
        s.errorRate = s.errorRate * (1 - ALPHA);
        synchronized (s.recentFailures) { s.recentFailures.clear(); }
        count(id, "success");
        if (registry != null) io.micrometer.core.instrument.Timer.builder("meter.routing.first_byte").tag("deployment", id).register(registry).record(Duration.ofMillis(ttfbMs));
    }

    /** A transient failure (timeout, 5xx, overloaded, connection error). Too many within a minute starts a cooldown. */
    public boolean transientFailure(String id, String reason, int allowedFails, int cooldownSeconds, Instant now) {
        var s = of(id);
        s.errorRate = s.errorRate * (1 - ALPHA) + ALPHA;
        s.lastError = reason;
        count(id, "transient_failure");
        synchronized (s.recentFailures) {
            s.recentFailures.addLast(now);
            while (!s.recentFailures.isEmpty() && s.recentFailures.peekFirst().isBefore(now.minusSeconds(60))) s.recentFailures.removeFirst();
            if (s.recentFailures.size() >= allowedFails) {
                s.cooldownUntil = now.plusSeconds(cooldownSeconds);
                s.recentFailures.clear();
                count(id, "cooldown");
                return true;
            }
        }
        return false;
    }

    /** The provider reported its quota as used up: skip it until the reset. */
    public void quotaExhausted(String id, Instant until, String reason) {
        var s = of(id);
        if (until.isAfter(s.quotaBlockedUntil)) s.quotaBlockedUntil = until;
        s.lastError = reason;
        count(id, "quota_exhausted");
    }

    public void otherFailure(String id, String reason) {
        of(id).lastError = reason;
        count(id, "rejected");
    }
}
