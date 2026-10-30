package dev.subscriptionmeter;

import dev.subscriptionmeter.domain.Member;
import dev.subscriptionmeter.limits.InMemoryRequestLimiter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestLimiterTest {
    @Test void memberConcurrencyIsIsolatedAndPermitsReleaseOnce() {
        var limiter = new InMemoryRequestLimiter();
        var a = new Member("a", "a", "", true, 60, 1);
        var b = new Member("b", "b", "", true, 60, 1);
        var first = limiter.acquire(a);
        assertThatThrownBy(() -> limiter.acquire(a)).hasMessageContaining("429");
        limiter.acquire(b).close(); first.close(); first.close();
        try (var second = limiter.acquire(a)) {
            assertThatThrownBy(() -> limiter.acquire(a)).hasMessageContaining("429");
        }
    }
    @Test void requestRateSurvivesCompletedRequests() {
        var limiter = new InMemoryRequestLimiter();
        var m = new Member("a", "a", "", true, 1, 4);
        limiter.acquire(m).close();
        assertThatThrownBy(() -> limiter.acquire(m)).hasMessageContaining("429");
    }
}
