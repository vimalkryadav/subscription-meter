package dev.subscriptionmeter.messaging;

/** Each part has an immutable identity and producer timestamp, preserved through replay. */
public record AuditEnvelope(int version, String operationId, String requestId, String kind,
                            String observedAt, int part, int parts, int payloadBytes,
                            String sha256, String data) { }
