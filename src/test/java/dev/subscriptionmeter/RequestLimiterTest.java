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
    @Test void noAggregateConcurrencyCap() {
        var limiter=new InMemoryRequestLimiter();
        var permits=new java.util.ArrayList<dev.subscriptionmeter.limits.RequestLimiter.Permit>();
        try {
            for(int user=0;user<9;user++) {
                var member=new Member("member-"+user,"member","",true,120,10);
                for(int request=0;request<10;request++) permits.add(limiter.acquire(member));
                assertThatThrownBy(()->limiter.acquire(member)).hasMessageContaining("429");
            }
            assertThat(permits).hasSize(90);
        } finally { permits.forEach(dev.subscriptionmeter.limits.RequestLimiter.Permit::close); }
    }
    @Test void requestRateSurvivesCompletedRequests() {
        var limiter = new InMemoryRequestLimiter();
        var m = new Member("a", "a", "", true, 1, 4);
        limiter.acquire(m).close();
        assertThatThrownBy(() -> limiter.acquire(m)).hasMessageContaining("429");
    }
}
