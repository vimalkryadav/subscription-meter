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
        /** Set when the reply was served by a different provider than the request's own (fallback routing). */
        default String routedProvider() { return null; }
        default String routedAccount() { return null; }
        default String servedModel() { return null; }
    }
}
