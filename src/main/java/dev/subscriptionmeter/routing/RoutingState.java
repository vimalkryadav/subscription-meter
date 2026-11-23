package dev.subscriptionmeter.routing;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * What this instance has learned about upstream quota, and which Claude Code sessions have moved to a
 * fallback. A moved session stays on the fallback (its history then lacks Claude's signed thinking
 * blocks, which Anthropic would reject mid tool loop). In-memory: a new instance relearns on first refusal.
 */
@Component
public class RoutingState {
    static final Duration STICKY = Duration.ofHours(6);
    private static final int MAX_STICKY = 20_000;
    private volatile Instant anthropicBlockedUntil = Instant.EPOCH, codexBlockedUntil = Instant.EPOCH;
    private volatile Integer codexUsedPercent;
    private volatile Instant codexResetAt;
    private final Map<String, Instant> sticky = new ConcurrentHashMap<>();

    public boolean anthropicBlocked(Instant now) { return anthropicBlockedUntil.isAfter(now); }
    public boolean codexBlocked(Instant now) { return codexBlockedUntil.isAfter(now); }

    public void blockAnthropic(Instant until) { if (until.isAfter(anthropicBlockedUntil)) anthropicBlockedUntil = until; }
    public void blockCodex(Instant until) { if (until.isAfter(codexBlockedUntil)) codexBlockedUntil = until; }

    /** Records Codex quota headers; a fully used window blocks Codex until it resets. */
    public void codexQuota(Integer usedPercent, Instant resetAt, Instant now) {
        if (usedPercent != null) codexUsedPercent = usedPercent;
        if (resetAt != null) codexResetAt = resetAt;
        if (usedPercent != null && usedPercent >= 100) blockCodex(resetAt != null && resetAt.isAfter(now) ? resetAt : now.plus(Duration.ofMinutes(15)));
    }

    public boolean stickyFallback(String session, Instant now) {
        if (session == null) return false;
        Instant until = sticky.get(session);
        if (until == null) return false;
        if (until.isBefore(now)) { sticky.remove(session); return false; }
        sticky.put(session, now.plus(STICKY));
        return true;
    }

    public void stick(String session, Instant now) {
        if (session == null) return;
        if (sticky.size() >= MAX_STICKY) sticky.entrySet().removeIf(e -> e.getValue().isBefore(now));
        if (sticky.size() < MAX_STICKY) sticky.put(session, now.plus(STICKY));
    }

    public Map<String, Object> status(Instant now) {
        var m = new LinkedHashMap<String, Object>();
        m.put("anthropicBlockedUntil", anthropicBlocked(now) ? anthropicBlockedUntil.toString() : null);
        m.put("codexBlockedUntil", codexBlocked(now) ? codexBlockedUntil.toString() : null);
        m.put("codexUsedPercent", codexUsedPercent);
        m.put("codexResetAt", codexResetAt == null ? null : codexResetAt.toString());
        m.put("sessionsOnFallback", sticky.values().stream().filter(t -> t.isAfter(now)).count());
        return m;
    }
}
