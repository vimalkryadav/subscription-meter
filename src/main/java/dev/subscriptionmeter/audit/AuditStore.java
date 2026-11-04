package dev.subscriptionmeter.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AuditStore {
    void begin(AuditContext context, String memberId, String mode, String headers, String query, String body);
    void event(String id, Instant requestTime, int sequence, long elapsedMs, String type, String data, boolean truncated);
    default void eventsBatch(List<AuditEvent> events) {
        for (AuditEvent e : events) event(e.id(),e.requestTime(),e.sequence(),e.elapsedMs(),e.type(),e.data(),e.truncated());
    }
    default boolean requiresAcknowledgedForwarding() { return false; }
    default boolean ready() { return true; }
    default void headers(String id,String redactedHeaders,long elapsedMs,boolean streaming) { }
    void finish(String id, Map<String, Object> fields);
    void access(AuditContext context, String memberId, int status, long elapsedMs, String headers, String query);
    List<Map<String, Object>> list(Instant since, String memberId, int limit, int offset);
    Optional<Map<String, Object>> detail(String id);
    List<Map<String, Object>> events(String id, int after, int limit);
    List<Map<String, Object>> accesses(Instant since, int limit, int offset);
}
