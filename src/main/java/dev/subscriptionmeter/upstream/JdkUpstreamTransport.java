package dev.subscriptionmeter.upstream;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JdkUpstreamTransport implements UpstreamTransport {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).factory());
    private final String origin;
    private final int timeout;
    private final UpstreamCredentialProvider credentials;
    public JdkUpstreamTransport(UpstreamCredentialProvider credentials,
            @Value("${meter.upstream-url}") String origin, @Value("${meter.mode}") String mode,
            @Value("${meter.timeout-seconds}") int timeout) {
        this.credentials = credentials; this.origin = origin.replaceAll("/$", ""); this.timeout = timeout;
        URI uri = URI.create(this.origin);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || !Set.of("http", "https").contains(uri.getScheme())) throw new IllegalArgumentException("Bare upstream origin required");
        if (mode.equals("subscription") && !this.origin.equals("https://api.anthropic.com"))
            throw new IllegalArgumentException("Subscription upstream must be https://api.anthropic.com");
    }
    public Reply send(String method, String path, Map<String, String> headers, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(timeout))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        request.header("Authorization", "Bearer " + credentials.bearerToken());
        request.header("Accept-Encoding", "identity");
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        ScheduledFuture<?> deadline = deadlines.schedule(() -> {
            try { response.body().close(); } catch (IOException ignored) { }
        }, timeout, TimeUnit.SECONDS);
        return new Reply() {
            public int status() { return response.statusCode(); }
            public Map<String, List<String>> headers() { return response.headers().map(); }
            public InputStream body() { return response.body(); }
            public void close() throws IOException { deadline.cancel(false); response.body().close(); }
        };
    }
    @PreDestroy void close() { deadlines.shutdownNow(); client.close(); }
}
