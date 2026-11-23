package dev.subscriptionmeter.summaries;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Calls the key broker's admin API on behalf of a signed-in gateway administrator. */
@Component
public class BrokerAdminClient {
    public record Reply(int status, String body) {}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;
    private final Path tokenFile;

    public BrokerAdminClient(@Value("${meter.broker.url:http://openai-broker:8190}") String base,
                             @Value("${meter.broker.admin-token-file:}") String tokenFile) {
        this.base = base.replaceAll("/+$", "");
        this.tokenFile = tokenFile == null || tokenFile.isBlank() ? null : Path.of(tokenFile);
    }

    private String token() {
        try {
            if (tokenFile != null && Files.isRegularFile(tokenFile)) {
                String value = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
                if (value.matches("[A-Za-z0-9_-]{32,200}")) return value;
            }
        } catch (IOException ignored) { }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Key broker is not configured");
    }

    public Reply call(String method, String path, String json, String actor) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token()).header("Content-Type", "application/json")
                .header("X-Broker-Actor", actor.replaceAll("[^A-Za-z0-9._:@-]", "_").substring(0, Math.min(80, actor.length())))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Key broker is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Key broker is unreachable");
        }
    }
}
