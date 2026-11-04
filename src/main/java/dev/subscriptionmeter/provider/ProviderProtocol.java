package dev.subscriptionmeter.provider;

import dev.subscriptionmeter.usage.UsageObserver;
import java.util.Map;

public interface ProviderProtocol {
    String id();
    UsageObserver observer(boolean streaming);
    boolean inference(String path);
    boolean responseHeader(String name);
    String requestId(Map<String,String> headers);
}
