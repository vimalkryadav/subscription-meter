package dev.subscriptionmeter.limits;

import dev.subscriptionmeter.domain.Member;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Single-instance prototype. Replace this adapter with a distributed limiter before scaling replicas. */
public class InMemoryRequestLimiter implements RequestLimiter {
    private final Map<String, Deque<Long>> windows = new HashMap<>();
    private final Map<String, Integer> active = new HashMap<>();
    public synchronized Permit acquire(Member m) {
        long now = System.nanoTime();
        Deque<Long> window = windows.computeIfAbsent(m.id(), ignored -> new ArrayDeque<>());
        while (!window.isEmpty() && window.peekFirst() < now - 60_000_000_000L) window.removeFirst();
        if (window.size() >= m.rpm() || active.getOrDefault(m.id(), 0) >= m.concurrency())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Member request limit reached");
        window.addLast(now); active.merge(m.id(), 1, Integer::sum);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) release(m.id()); };
    }
    private synchronized void release(String id) { active.computeIfPresent(id, (k, n) -> n - 1); }
}
