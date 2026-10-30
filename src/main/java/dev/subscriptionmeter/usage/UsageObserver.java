package dev.subscriptionmeter.usage;
import dev.subscriptionmeter.domain.Usage;

public interface UsageObserver {
    void accept(byte[] bytes, int length);
    Usage snapshot();
    boolean complete();
    boolean error();
}
