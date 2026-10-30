package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditCaptureTest {
    private final CredentialRedactor redact=new CredentialRedactor();
    private AuditContext context() { return new AuditContext("req",Instant.now(),"POST","/v1/messages",
            "beta=true&access_token=never-store",Map.of("Authorization",List.of("Bearer secret"),
            "Cookie",List.of("session=secret"),"x-coder-token",List.of("custom-secret"),
            "anthropic-version",List.of("2023-06-01")),"127.0.0.1","HTTP/1.1"); }
    @Test void recordsNestedToolsWithoutPersistingCredentials() {
        var store=mock(AuditStore.class);
        String request="{\"system\":\"Be helpful\",\"tools\":[{\"name\":\"test\"}],\"messages\":[{\"content\":\"hello\"}],\"password\":\"hidden-value\",\"max_tokens\":25}";
        var capture=new AuditCapture(store,redact,new SimpleMeterRegistry(),context(),"member","mock",request.getBytes(),1024,100);
        var body=ArgumentCaptor.forClass(String.class);
        verify(store).begin(any(),eq("member"),eq("mock"),eq("{\"anthropic-version\":[\"2023-06-01\"]}"),
                eq("beta=true&access_token=[REDACTED]"),body.capture());
        assertThat(body.getValue()).contains("Be helpful","tools","messages","25","[REDACTED]").doesNotContain("hidden-value");
    }
    @Test void splitSseEventsRemainOrderedAndNestedSecretsAreRedacted() {
        var store=mock(AuditStore.class);
        var capture=new AuditCapture(store,redact,new SimpleMeterRegistry(),context(),"member","mock","{}".getBytes(),4096,100);
        capture.headers(Map.of("content-type",List.of("text/event-stream"),"set-cookie",List.of("secret")),true);
        String data="event: content_block_delta\r\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hello 🌏\",\"secret\":\"hide-this\"}}\r\n\r\n"
                +"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        for(byte b:data.getBytes(StandardCharsets.UTF_8))capture.bytes(new byte[]{b},1);
        capture.finish("complete");
        verify(store).event(eq("req"),any(),eq(1),anyLong(),eq("content_block_delta"),argThat(s->s.contains("hello 🌏")&&!s.contains("hide-this")),eq(false));
        verify(store).event(eq("req"),any(),eq(2),anyLong(),eq("message_stop"),anyString(),eq(false));
        verify(store).finish(eq("req"),argThat(m->m.get("capture_state").equals("complete")&&m.get("event_count").equals(2)
                &&m.get("response_body").equals("")&&!m.get("response_headers").toString().contains("cookie")));
    }
    @Test void responseLimitIsExplicitAndByteCountStillIncludesEverything() {
        var store=mock(AuditStore.class);
        var capture=new AuditCapture(store,redact,new SimpleMeterRegistry(),context(),"member","mock",new byte[0],16,100);
        capture.headers(Map.of(),false);
        byte[] data="{\"message\":\"This response exceeds the configured capture limit\"}".getBytes();
        capture.bytes(data,data.length); capture.finish("complete");
        verify(store).finish(eq("req"),argThat(m->m.get("capture_state").equals("truncated")
                &&m.get("truncated").equals(true)&&m.get("response_bytes").equals((long)data.length)
                &&m.get("captured_bytes").equals(16)));
    }
    @Test void eventLimitIsExplicit() {
        var store=mock(AuditStore.class);
        var capture=new AuditCapture(store,redact,new SimpleMeterRegistry(),context(),"member","mock",new byte[0],4096,1);
        capture.headers(Map.of(),true);
        byte[] data="data: {\"type\":\"ping\"}\n\ndata: {\"type\":\"message_stop\"}\n\n".getBytes();
        capture.bytes(data,data.length);capture.finish("complete");
        verify(store,times(1)).event(anyString(),any(),anyInt(),anyLong(),anyString(),anyString(),anyBoolean());
        verify(store).finish(eq("req"),argThat(m->m.get("capture_state").equals("truncated")));
    }
    @Test void captureFailureDoesNotThrowAwayTheResponseAndIsCounted() {
        var store=mock(AuditStore.class);
        var metrics=new SimpleMeterRegistry();
        doThrow(new IllegalStateException("db unavailable")).when(store).event(anyString(),any(),anyInt(),anyLong(),anyString(),anyString(),anyBoolean());
        var capture=new AuditCapture(store,redact,metrics,context(),"member","mock",new byte[0],4096,100);
        capture.headers(Map.of(),true);
        byte[] data="data: {\"type\":\"message_stop\"}\n\n".getBytes();
        capture.bytes(data,data.length);capture.finish("complete");
        verify(store).finish(eq("req"),argThat(m->m.get("capture_state").equals("capture_error")));
        assertThat(metrics.counter("meter.audit.failures").count()).isEqualTo(1);
    }
    @Test void redactsRecognizableSecretsInPlainTextAndIncompleteJson() {
        String secret="sk-ant-"+"a".repeat(32);
        assertThat(redact.payload("{\"password\":\"hidden-value\",\"unfinished\":"))
                .doesNotContain("hidden-value");
        assertThat(redact.text("api_key='hidden-value' "+secret+" Bearer opaque-secret"))
                .doesNotContain("hidden-value",secret,"opaque-secret");
    }
}
