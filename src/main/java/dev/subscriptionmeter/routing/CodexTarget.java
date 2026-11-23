package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.provider.OpenAIPlanCredentialFile;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The shared Codex (ChatGPT plan) login, via the same backend the Codex CLI uses. Reads only the
 * access token the refresher publishes; the ChatGPT account id comes from the token's own claims.
 */
@Component
public class CodexTarget implements ResponsesTarget {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Path tokenFile;
    private final URI endpoint;
    private final RoutingState state;
    private final QuotaBalancer balancer;
    private final String account;
    private volatile Set<String> models;
    private volatile Instant modelsAt = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public CodexTarget(@Value("${meter.codex.token-file:}") String tokenFile,
                       @Value("${meter.codex.url:https://chatgpt.com/backend-api/codex/responses}") String url, RoutingState state, QuotaBalancer balancer) {
        this(tokenFile, url, state, balancer, "codex");
    }

    /** An additional Codex login (its own token file and account label). */
    public CodexTarget(String tokenFile, String url, RoutingState state, QuotaBalancer balancer, String account) {
        this.tokenFile = tokenFile == null || tokenFile.isBlank() ? null : Path.of(tokenFile);
        this.endpoint = URI.create(url);
        this.state = state;
        this.balancer = balancer;
        this.account = account;
    }

    /** Models this login offers (refreshed every 30 minutes); empty when not yet known. */
    public Optional<Set<String>> models() {
        Instant now = Instant.now();
        if (models != null && modelsAt.plus(Duration.ofMinutes(30)).isAfter(now)) return Optional.of(models);
        var credential = credential();
        if (credential.isEmpty()) return Optional.ofNullable(models);
        try {
            String url = endpoint.toString().replaceAll("/responses$", "/models") + "?client_version=0.171.0";
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + credential.get().token()).header("chatgpt-account-id", credential.get().accountId())
                    .header("originator", "codex_cli_rs").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                var found = new HashSet<String>();
                for (JsonNode m : AnthropicToResponses.JSON.readTree(response.body()).path("models")) {
                    String slug = m.path("slug").asString(m.path("id").asString(""));
                    if (!slug.isEmpty()) found.add(slug);
                }
                if (!found.isEmpty()) { models = Set.copyOf(found); modelsAt = now; }
            }
        } catch (IOException | RuntimeException e) { /* keep the previous list */ }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Optional.ofNullable(models);
    }

    static String bucket(Integer windowMinutes, String fallback) {
        if (windowMinutes == null || windowMinutes <= 0) return null;
        return windowMinutes == 10080 ? "seven_day" : windowMinutes == 300 ? "five_hour" : fallback;
    }

    public String account() { return account; }

    record Credential(String token, String accountId) {}

    static String accountId(String jwt) {
        try {
            String payload = jwt.split("\\.")[1];
            payload += "=".repeat((4 - payload.length() % 4) % 4);
            JsonNode claims = AnthropicToResponses.JSON.readTree(Base64.getUrlDecoder().decode(payload));
            String id = claims.path("https://api.openai.com/auth").path("chatgpt_account_id").asString("");
            return id.matches("[A-Za-z0-9-]{1,100}") ? id : null;
        } catch (RuntimeException e) { return null; }
    }

    Optional<Credential> credential() {
        if (tokenFile == null) return Optional.empty();
        try {
            String token = new OpenAIPlanCredentialFile(tokenFile).bearerToken();
            String account = accountId(token);
            return account == null ? Optional.empty() : Optional.of(new Credential(token, account));
        } catch (IOException e) { return Optional.empty(); }
    }

    public boolean available() { return credential().isPresent(); }

    public HttpResponse<InputStream> send(byte[] body, String memberId, String session) throws IOException, InterruptedException {
        var credential = credential().orElseThrow(() -> new IOException("Codex login unavailable"));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(30))
                .header("Authorization", "Bearer " + credential.token()).header("chatgpt-account-id", credential.accountId())
                .header("originator", "codex_cli_rs").header("OpenAI-Beta", "responses=experimental")
                .header("Content-Type", "application/json").header("Accept", "text/event-stream");
        if (session != null) request.header("session_id", session);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    static Integer intHeader(HttpResponse<?> r, String name) {
        try { return r.headers().firstValue(name).map(String::strip).filter(s -> !s.isEmpty()).map(Integer::parseInt).orElse(null); }
        catch (NumberFormatException e) { return null; }
    }

    public void observe(HttpResponse<?> response) {
        Instant now = Instant.now();
        Integer used = intHeader(response, "x-codex-primary-used-percent");
        Integer resetAt = intHeader(response, "x-codex-primary-reset-at");
        Integer secondary = intHeader(response, "x-codex-secondary-used-percent");
        Integer secondaryReset = intHeader(response, "x-codex-secondary-reset-at");
        state.codexQuota(used, resetAt == null ? null : Instant.ofEpochSecond(resetAt), now);
        // Shared, persistent observations (also shown on the quota dashboard) drive balanced routing.
        String primary = bucket(intHeader(response, "x-codex-primary-window-minutes"), "primary");
        if (primary != null && resetAt != null) balancer.recordCodex(account, primary, used, Instant.ofEpochSecond(resetAt), now);
        String second = bucket(intHeader(response, "x-codex-secondary-window-minutes"), "secondary");
        if (second != null && secondaryReset != null) balancer.recordCodex(account, second, secondary, Instant.ofEpochSecond(secondaryReset), now);
        if (secondary != null && secondary >= 100) state.blockCodex(secondaryReset == null ? now.plus(Duration.ofMinutes(15)) : Instant.ofEpochSecond(secondaryReset));
        if (response.statusCode() == 429) {
            Integer after = intHeader(response, "x-codex-primary-reset-after-seconds");
            state.blockCodex(resetAt != null ? Instant.ofEpochSecond(resetAt) : after != null ? now.plusSeconds(after) : now.plus(Duration.ofMinutes(15)));
        }
    }

    static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
}
