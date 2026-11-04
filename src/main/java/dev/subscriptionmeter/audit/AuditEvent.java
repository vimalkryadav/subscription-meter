package dev.subscriptionmeter.audit;

import java.time.Instant;

public record AuditEvent(String id, Instant requestTime, int sequence, long elapsedMs,
                         String type, String data, boolean truncated) { }
