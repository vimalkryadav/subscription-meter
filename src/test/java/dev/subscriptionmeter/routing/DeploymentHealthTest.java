package dev.subscriptionmeter.routing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeploymentHealthTest {
    static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    @Test void failuresWithinAMinuteStartACooldownAndSuccessResetsTheCount() throws Exception {
        var registry = new SimpleMeterRegistry();
        var health = new DeploymentHealth(registry);
        assertThat(health.transientFailure("d", "HTTP 503", 3, 60, NOW)).isFalse();
        health.success("d", 900);
        assertThat(health.transientFailure("d", "HTTP 503", 3, 60, NOW)).isFalse();
        assertThat(health.transientFailure("d", "HTTP 503", 3, 60, NOW.plusSeconds(1))).isFalse();
        // A failure older than a minute no longer counts towards the cooldown.
        assertThat(health.transientFailure("d", "HTTP 503", 3, 60, NOW.plusSeconds(90))).isFalse();
        assertThat(health.transientFailure("d", "HTTP 503", 3, 60, NOW.plusSeconds(91))).isFalse();
        assertThat(health.transientFailure("d", "HTTP 503", 3, 60, NOW.plusSeconds(92))).isTrue();
        var view = health.view("d");
        assertThat(view.usable(NOW.plusSeconds(100))).isFalse();
        assertThat(view.usable(NOW.plusSeconds(153))).isTrue();
        assertThat(view.lastError()).isEqualTo("HTTP 503");
        assertThat(view.errorRate()).isGreaterThan(0.3);
        assertThat(registry.get("meter.routing.attempts").tag("deployment", "d").tag("outcome", "cooldown").counter().count()).isEqualTo(1);
    }

    @Test void tracksInFlightLatencyAndQuotaBlocks() throws Exception {
        var health = new DeploymentHealth(new SimpleMeterRegistry());
        var a = health.acquire("d"); var b = health.acquire("d");
        assertThat(health.view("d").inFlight()).isEqualTo(2);
        a.close(); a.close(); // closing twice releases once
        assertThat(health.view("d").inFlight()).isEqualTo(1);
        b.close();
        health.success("d", 1000); health.success("d", 2000);
        assertThat(health.view("d").ttfbMs()).isEqualTo(1200.0);
        health.quotaExhausted("d", NOW.plusSeconds(3600), "quota: seven_day");
        assertThat(health.view("d").usable(NOW)).isFalse();
        assertThat(health.view("d").usable(NOW.plusSeconds(3601))).isTrue();
    }
}
