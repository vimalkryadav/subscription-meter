package dev.subscriptionmeter.upstream;

import java.io.*;
import java.util.*;

public interface UpstreamTransport {
    Reply send(String method, String pathAndQuery, Map<String, String> headers, byte[] body)
            throws IOException, InterruptedException;
    interface Reply extends AutoCloseable {
        int status();
        Map<String, List<String>> headers();
        InputStream body();
        @Override void close() throws IOException;
    }
}
