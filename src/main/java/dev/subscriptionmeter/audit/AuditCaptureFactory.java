package dev.subscriptionmeter.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuditCaptureFactory {
    private final AuditStore store;
    private final CredentialRedactor redactor;
    private final MeterRegistry metrics;
    private final String mode;
    private final int maxBytes, maxEvents;
    public AuditCaptureFactory(AuditStore store, CredentialRedactor redactor, MeterRegistry metrics,
            @Value("${meter.mode}") String mode, @Value("${meter.audit.max-response-bytes:8388608}") int maxBytes,
            @Value("${meter.audit.max-events:10000}") int maxEvents) {
        this.store=store; this.redactor=redactor; this.metrics=metrics; this.mode=mode;
        if (maxBytes < 1024 || maxBytes > 33554432 || maxEvents < 1 || maxEvents > 100000)
            throw new IllegalArgumentException("Audit bounds out of range");
        this.maxBytes=maxBytes; this.maxEvents=maxEvents;
    }
    public AuditCapture begin(AuditContext context, String memberId, byte[] body) {
        return new AuditCapture(store, redactor, metrics, context, memberId, mode, body, maxBytes, maxEvents);
    }
}
