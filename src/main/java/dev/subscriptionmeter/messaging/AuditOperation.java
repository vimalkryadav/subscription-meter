package dev.subscriptionmeter.messaging;

import java.util.Map;

public record AuditOperation(String operationId, String requestId, String kind,
                             String observedAt, Map<String,Object> fields) { }
