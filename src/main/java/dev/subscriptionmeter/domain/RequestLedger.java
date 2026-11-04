package dev.subscriptionmeter.domain;

public interface RequestLedger {
    default void identify(String id,String provider,String account,String client) { }
    void begin(String id, String memberId, String path, String model);
    default void begin(String id,String memberId,String path,String model,java.time.Instant startedAt) {
        begin(id,memberId,path,model);
    }
    void finish(String id, RequestResult result);
    void markInterrupted();
}
