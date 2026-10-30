package dev.subscriptionmeter;

import dev.subscriptionmeter.usage.AnthropicUsageObserver;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UsageObserverTest {
    private static String event(String body) { return "event: ignored\r\ndata: " + body + "\r\n\r\n"; }
    @Test void arbitraryByteBoundariesAndCumulativeOutput() {
        String sse = event("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":120,\"output_tokens\":1,\"cache_creation_input_tokens\":30,\"cache_read_input_tokens\":80}}}")
            + event("{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hello 🌏\"}}")
            + event("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":8}}")
            + event("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":17}}")
            + event("{\"type\":\"message_stop\"}");
        var observer = new AnthropicUsageObserver(true);
        for (byte b : sse.getBytes(StandardCharsets.UTF_8)) observer.accept(new byte[]{b}, 1);
        assertThat(observer.complete()).isTrue();
        assertThat(observer.snapshot()).hasFieldOrPropertyWithValue("input", 120L)
                .hasFieldOrPropertyWithValue("output", 17L).hasFieldOrPropertyWithValue("cacheCreation", 30L)
                .hasFieldOrPropertyWithValue("cacheRead", 80L);
    }
    @Test void missingStopIsIncompleteEvenWithUsage() {
        var observer = new AnthropicUsageObserver(true);
        byte[] data = event("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}").getBytes();
        observer.accept(data, data.length);
        assertThat(observer.complete()).isFalse();
        assertThat(observer.snapshot().output()).isEqualTo(5);
    }
    @Test void streamingErrorRemainsErrorEvenWithStop() {
        var observer = new AnthropicUsageObserver(true);
        byte[] data = (event("{\"type\":\"error\"}") + event("{\"type\":\"message_stop\"}")).getBytes();
        observer.accept(data, data.length);
        assertThat(observer.error()).isTrue(); assertThat(observer.complete()).isFalse();
    }
    @Test void badJsonCannotBeCountedAsComplete() {
        var observer = new AnthropicUsageObserver(true);
        byte[] data = (event("not-json") + event("{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}")
                + event("{\"type\":\"message_stop\"}")).getBytes();
        observer.accept(data, data.length); assertThat(observer.complete()).isFalse();
    }
    @Test void nonStreamingUsageIsRead() {
        var observer = new AnthropicUsageObserver(false);
        byte[] data = "{\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}".getBytes();
        observer.accept(data, data.length); observer.finishJson();
        assertThat(observer.complete()).isTrue(); assertThat(observer.snapshot().input()).isEqualTo(12);
    }
    @Test void zeroUsageIsObservedRatherThanMissing() {
        var observer = new AnthropicUsageObserver(false);
        byte[] data = "{\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}".getBytes();
        observer.accept(data, data.length); observer.finishJson();
        assertThat(observer.snapshot().seen()).isTrue(); assertThat(observer.complete()).isTrue();
    }
}
