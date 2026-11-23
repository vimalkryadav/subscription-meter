package dev.subscriptionmeter.routing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The last tier: the OpenAI key broker, called with the member's own virtual key (budget-capped). */
@Component
public class BrokerTarget implements ResponsesTarget {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final MemberBrokerKeys keys;

    public BrokerTarget(@Value("${meter.broker.url:http://openai-broker:8190}") String base, MemberBrokerKeys keys) {
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/v1/responses");
        this.keys = keys;
    }

    public String account() { return "broker"; }
    public boolean available() { return true; }

    public HttpResponse<InputStream> send(byte[] body, String memberId, String session) throws IOException, InterruptedException {
        String key = keys.forMember(memberId).orElseThrow(() -> new IOException("No broker key for member"));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(30))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }
}
