package dev.subscriptionmeter.deployment;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Admission and in-flight accounting share one monitor, closing the drain/acquire race. */
@Component
public class DeploymentState {
    private boolean draining;
    private int active;
    private int unsettled;
    public synchronized dev.subscriptionmeter.limits.RequestLimiter.Permit enter() {
        if(draining) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Instance draining");
        return trackAudit();
    }
    /** Tracks servlet-level audit finalization without admitting an upstream request during drain. */
    public synchronized dev.subscriptionmeter.limits.RequestLimiter.Permit trackAudit() {
        active++;
        AtomicBoolean closed=new AtomicBoolean();
        return () -> { if(closed.compareAndSet(false,true)) synchronized(this) { active--; } };
    }
    public synchronized void drain() { draining=true; }
    public synchronized void resume() { draining=false; }
    public synchronized boolean draining() { return draining; }
    public synchronized int active() { return active; }
    public synchronized void persistenceFailed() { unsettled++; }
    public synchronized int unsettled() { return unsettled; }
}
