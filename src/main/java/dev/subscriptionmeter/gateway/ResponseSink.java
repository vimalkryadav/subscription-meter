package dev.subscriptionmeter.gateway;
import java.io.IOException;
import java.util.Map;

public interface ResponseSink {
    void start(int status, Map<String, String> headers);
    void write(byte[] data, int length) throws IOException;
}
