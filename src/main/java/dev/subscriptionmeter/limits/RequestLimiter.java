package dev.subscriptionmeter.limits;
import dev.subscriptionmeter.domain.Member;

public interface RequestLimiter {
    Permit acquire(Member member);
    interface Permit extends AutoCloseable { @Override void close(); }
}
