package dev.subscriptionmeter.gateway;
import java.io.IOException;
import java.util.Map;

public interface ResponseSink {
    void start(int status, Map<String, String> headers);
    /** Override when a partial failed write may already have committed HTTP headers. */
    default boolean committed() { return false; }
    /** Remove prepared upstream headers/body before returning an uncommitted gateway error. */
    default void prepareError(int status) {}
    void write(byte[] data, int length) throws IOException;
}
