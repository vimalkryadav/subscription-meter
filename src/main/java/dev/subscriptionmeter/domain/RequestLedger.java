package dev.subscriptionmeter.domain;

public interface RequestLedger {
    void begin(String id, String memberId, String path, String model);
    void finish(String id, RequestResult result);
    void markInterrupted();
}
