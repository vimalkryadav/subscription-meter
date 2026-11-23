package dev.subscriptionmeter.gateway;

import dev.subscriptionmeter.audit.AuditContext;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ClientLabelTest {
    static AuditContext context(Map<String, List<String>> headers) {
        return new AuditContext("id", Instant.now(), "POST", "/v1/messages", null, headers, "", "HTTP/1.1");
    }
    @Test void keepsOnlyOneBoundedSafeValue() {
        String id = "3f0c2a5e-1b2c-4d5e-8f90-123456789abc";
        assertThat(GatewayService.clientLabel(context(Map.of("X-Claude-Code-Session-Id", List.of(id))), "x-claude-code-session-id")).isEqualTo(id);
        assertThat(GatewayService.clientLabel(context(Map.of("x-claude-code-session-id", List.of("a", "b"))), "x-claude-code-session-id")).isNull();
        assertThat(GatewayService.clientLabel(context(Map.of("x-claude-code-session-id", List.of("x".repeat(101)))), "x-claude-code-session-id")).isNull();
        assertThat(GatewayService.clientLabel(context(Map.of("x-claude-code-session-id", List.of("a b<script>"))), "x-claude-code-session-id")).isNull();
        assertThat(GatewayService.clientLabel(context(Map.of()), "x-claude-code-session-id")).isNull();
    }
}
