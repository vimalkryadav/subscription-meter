package dev.subscriptionmeter;

import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.gateway.*;
import dev.subscriptionmeter.limits.InMemoryRequestLimiter;
import dev.subscriptionmeter.upstream.UpstreamTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayServiceTest {
    private final Member member = new Member("a", "A", "sm", true, 60, 1);
    private UpstreamTransport transport(int status, String type, String body) {
        return (method, path, headers, data) -> new UpstreamTransport.Reply() {
            private final InputStream input = new ByteArrayInputStream(body.getBytes());
            public int status() { return status; }
            public Map<String, List<String>> headers() { return Map.of("content-type", List.of(type), "request-id", List.of("req_1")); }
            public InputStream body() { return input; }
            public void close() throws IOException { input.close(); }
        };
    }
    @Test void nonStreamingBytesAndUsagePreserved() {
        String body = "{\"usage\":{\"input_tokens\":12,\"output_tokens\":3},\"content\":[{\"text\":\"hello\"}]}";
        var ledger = mock(RequestLedger.class);
        var service = new GatewayService(new InMemoryRequestLimiter(), transport(200, "application/json", body), ledger,
                new GatewayMetrics(new SimpleMeterRegistry()));
        var bytes = new ByteArrayOutputStream();
        service.forward(member, "POST", "/v1/messages", null, "test", Map.of(), "{}".getBytes(), sink(bytes));
        assertThat(bytes.toString()).isEqualTo(body);
        verify(ledger).finish(anyString(), argThat(r -> r.state().equals("complete") && r.usage().input() == 12 && r.usage().output() == 3));
    }
    @Test void upstream429IsRecordedAndPassedThrough() {
        var ledger = mock(RequestLedger.class);
        var service = new GatewayService(new InMemoryRequestLimiter(), transport(429, "application/json", "{\"error\":\"rate_limit\"}"), ledger,
                new GatewayMetrics(new SimpleMeterRegistry()));
        var result = new int[1];
        service.forward(member, "POST", "/v1/messages", null, "test", Map.of(), "{}".getBytes(), new ResponseSink() {
            public void start(int status, Map<String,String> headers) { result[0] = status; }
            public void write(byte[] b, int n) { }
        });
        assertThat(result[0]).isEqualTo(429);
        verify(ledger).finish(anyString(), argThat(r -> r.status() == 429 && r.state().equals("upstream_error")));
    }
    @Test void clientDisconnectPersistsPartialUsageAndReleasesPermit() {
        var ledger = mock(RequestLedger.class);
        var limiter = new InMemoryRequestLimiter();
        String data = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":9}}}\n\n";
        var service = new GatewayService(limiter, transport(200, "text/event-stream", data), ledger,
                new GatewayMetrics(new SimpleMeterRegistry()));
        service.forward(member, "POST", "/v1/messages", null, "test", Map.of(), "{}".getBytes(), new ResponseSink() {
            public void start(int status, Map<String,String> headers) { }
            public void write(byte[] b, int n) throws IOException { throw new IOException("client gone"); }
        });
        verify(ledger).finish(anyString(), argThat(r -> r.state().equals("disconnected") && r.usage().input() == 9));
        limiter.acquire(member).close();
    }
    @Test void missingCredentialDoesNotLeavePendingRequest() {
        var ledger = mock(RequestLedger.class);
        UpstreamTransport broken = (method, path, headers, data) -> { throw new IOException("missing"); };
        var service = new GatewayService(new InMemoryRequestLimiter(), broken, ledger, new GatewayMetrics(new SimpleMeterRegistry()));
        assertThatThrownBy(() -> service.forward(member, "POST", "/v1/messages", null, "test", Map.of(), new byte[0], sink(new ByteArrayOutputStream())))
                .hasMessageContaining("502");
        verify(ledger).finish(anyString(), argThat(r -> r.state().equals("upstream_error") && r.status() == 502));
    }
    private ResponseSink sink(ByteArrayOutputStream bytes) {
        return new ResponseSink() {
            public void start(int status, Map<String,String> headers) { }
            public void write(byte[] b, int n) { bytes.write(b, 0, n); }
        };
    }
}
