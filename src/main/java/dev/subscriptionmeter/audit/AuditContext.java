package dev.subscriptionmeter.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AuditContext(String id, Instant startedAt, String method, String path, String query,
                           Map<String, List<String>> headers, String peerAddress, String protocol) { }
