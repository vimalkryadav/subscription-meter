package dev.subscriptionmeter.routing;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.Optional;

/** An OpenAI Responses endpoint the router can fall back to. */
public interface ResponsesTarget {
    String account();
    boolean available();
    /** Streams a Responses request; the caller owns (and must close) the returned body. */
    HttpResponse<InputStream> send(byte[] body, String memberId, String session) throws IOException, InterruptedException;
    /** Notes quota information from a response (success or refusal). */
    default void observe(HttpResponse<?> response) { }
    /** True when a refusal means this tier is out of quota/budget (try the next tier). */
    default boolean exhausted(int status, String body) { return status == 429; }
    default Optional<String> unavailableReason() { return Optional.empty(); }
}
