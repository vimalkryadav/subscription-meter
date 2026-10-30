package dev.subscriptionmeter.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One per request. Persists complete SSE events, not arbitrary TCP chunk boundaries. */
public final class AuditCapture {
    private final AuditStore store;
    private final CredentialRedactor redact;
    private final MeterRegistry metrics;
    private final AuditContext context;
    private final long start = System.nanoTime();
    private final int requestBytes, maxBytes, maxEvents;
    private final ByteArrayOutputStream response = new ByteArrayOutputStream();
    private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
    private long responseBytes, firstByteMs = -1, headersMs = -1;
    private int sequence, storedEvents, previousByte, capturedBytes;
    private boolean streaming, truncated, failed, discardFrame;
    private String responseHeaders = "{}", errorType = "", errorMessage = "";

    public AuditCapture(AuditStore store, CredentialRedactor redact, MeterRegistry metrics, AuditContext context,
                        String memberId, String mode, byte[] body, int maxBytes, int maxEvents) {
        this.store=store; this.redact=redact; this.metrics=metrics; this.context=context;
        this.requestBytes=body.length; this.maxBytes=maxBytes; this.maxEvents=maxEvents;
        // Fail closed before sending upstream if the request cannot be durably recorded.
        store.begin(context, memberId, mode, redact.headers(context.headers()), redact.query(context.query()),
                redact.payload(new String(body, StandardCharsets.UTF_8)));
    }
    private long elapsed() { return (System.nanoTime() - start) / 1_000_000; }
    public void headers(Map<String,List<String>> headers, boolean streaming) {
        this.streaming=streaming; this.responseHeaders=redact.headers(headers); headersMs=elapsed();
    }
    public void bytes(byte[] bytes, int length) {
        if (firstByteMs < 0) firstByteMs=elapsed();
        responseBytes += length;
        int remaining = maxBytes - capturedBytes;
        int capture = Math.min(length, remaining);
        if (capture < length) truncated=true;
        capturedBytes += capture;
        if (!streaming) response.write(bytes, 0, capture);
        if (!streaming) return;
        for (int i=0; i<capture; i++) {
            byte b=bytes[i];
            if (!discardFrame) frame.write(b);
            // SSE blank lines; CR bytes do not affect detection of LF LF.
            if (b != '\r') {
                if (b == '\n' && previousByte == '\n') {
                    if (!discardFrame) persistFrame(false);
                    frame.reset(); discardFrame=false;
                }
                previousByte=b;
            }
            if (frame.size() > 524288) { truncated=true; discardFrame=true; frame.reset(); }
        }
    }
    private void persistFrame(boolean partial) {
        if (frame.size()==0) return;
        sequence++;
        if (storedEvents >= maxEvents) { truncated=true; return; }
        String text=frame.toString(StandardCharsets.UTF_8);
        String type="message";
        StringBuilder data=new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            if (line.startsWith("event:")) type=line.substring(6).strip();
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        if (data.isEmpty()) { type="comment"; data.append(text); }
        try {
            String safeType=redact.text(type);
            store.event(context.id(),context.startedAt(),sequence,elapsed(),safeType.substring(0, Math.min(safeType.length(),100)),
                    redact.payload(data.toString()),partial);
            storedEvents++;
        } catch (RuntimeException e) { captureFailed(); }
    }
    private void captureFailed() { failed=true; metrics.counter("meter.audit.failures").increment(); }
    public void error(Throwable error) {
        errorType=error.getClass().getSimpleName(); errorMessage=redact.text(error.getMessage());
    }
    public void finish(String state) {
        try { finishInternal(state); } catch (RuntimeException e) { captureFailed(); }
    }
    private void finishInternal(String state) {
        if (streaming && frame.size()>0 && !discardFrame) persistFrame(true);
        String raw=response.toString(StandardCharsets.UTF_8);
        // SSE is represented by its individually redacted events. Storing raw SSE too could
        // bypass nested-field redaction. Non-streaming replies retain a redacted full body.
        String body=streaming ? "" : redact.payload(raw);
        Map<String,Object> fields=new HashMap<>();
        fields.put("capture_state",failed?"capture_error":truncated?"truncated":state);
        fields.put("response_headers",responseHeaders); fields.put("response_body",body);
        fields.put("response_bytes",responseBytes); fields.put("captured_bytes",capturedBytes);
        fields.put("request_bytes",requestBytes); fields.put("event_count",storedEvents);
        fields.put("truncated",truncated); fields.put("upstream_headers_ms",headersMs<0?null:headersMs);
        fields.put("first_byte_ms",firstByteMs<0?null:firstByteMs);
        fields.put("error_type",errorType); fields.put("error_message",errorMessage);
        try { store.finish(context.id(),fields); } catch (RuntimeException e) { captureFailed(); }
        if (truncated) metrics.counter("meter.audit.truncated").increment();
    }
}
