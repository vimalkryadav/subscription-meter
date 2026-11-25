package dev.subscriptionmeter.web;

import dev.subscriptionmeter.deployment.DeploymentState;
import dev.subscriptionmeter.portal.DirectVirtualKeys;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Relays only supported direct API routes, and only for provisioned direct keys. */
@RestController
@RequestMapping("/api/v1")
public class DirectApiController {
    private static final int MAX_BODY = 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("model", "input", "instructions", "max_output_tokens", "stream", "store");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Semaphore slots = new Semaphore(16);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().factory());
    private final DirectVirtualKeys keys;
    private final DeploymentState deployment;
    private final String broker;

    public DirectApiController(DirectVirtualKeys keys, DeploymentState deployment,
                               @Value("${meter.broker.url:http://openai-broker:8190}") String broker) {
        this.keys = keys; this.deployment = deployment; this.broker = broker.replaceAll("/+$", "");
        deadlines.setRemoveOnCancelPolicy(true);
    }

    @PreDestroy public void close() { deadlines.shutdownNow(); http.close(); }

    @GetMapping("/models")
    public void models(HttpServletRequest request, HttpServletResponse response) throws IOException {
        relay(request, response, false);
    }

    @PostMapping(path = "/responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void responses(HttpServletRequest request, HttpServletResponse response) throws IOException {
        relay(request, response, true);
    }

    private final Semaphore embeddingSlots = new Semaphore(4);

    @PostMapping(path = "/embeddings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void embeddings(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.matches("(?i:Bearer) cuvk_[A-Za-z0-9_-]{43}") || !keys.authorized(auth.substring(7))) {
            error(response, 401, "invalid_api_key", "Direct API key is invalid or inactive"); return;
        }
        if (request.getQueryString() != null) { error(response, 400, "invalid_request", "Query parameters are not supported"); return; }
        if (!embeddingSlots.tryAcquire()) { response.setHeader("Retry-After", "1"); error(response, 429, "overloaded", "Embeddings capacity is busy"); return; }
        try (var tracking = deployment.enter()) {
            int limit = 8 * 1024 * 1024;
            if (request.getContentLengthLong() > limit) { error(response, 413, "request_too_large", "Request exceeds 8 MiB"); return; }
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) { error(response, 413, "request_too_large", "Request exceeds 8 MiB"); return; }
            var upstream = http.send(HttpRequest.newBuilder(URI.create(broker + "/v1/embeddings"))
                    .timeout(Duration.ofSeconds(65)).header("Authorization", auth).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = upstream.body()) {
                var timeout = deadlines.schedule(() -> { try { stream.close(); } catch (IOException ignored) { } }, 65, TimeUnit.SECONDS);
                try {
                    int status = upstream.statusCode();
                    if (status == 429) upstream.headers().firstValue("Retry-After").filter(v -> v.matches("[0-9]{1,9}")).ifPresent(v -> response.setHeader("Retry-After", v));
                    if (status != 200) {
                        int mapped = java.util.Set.of(400,401,413,422,429,504).contains(status) ? status : 502;
                        error(response, mapped, switch (mapped) { case 400,422 -> "invalid_request"; case 401 -> "invalid_api_key"; case 413 -> "request_too_large"; case 429 -> "limit_reached"; default -> "upstream_error"; }, "Embeddings request did not succeed");
                        return;
                    }
                    response.setStatus(200);
                    response.setContentType("application/json");
                    stream.transferTo(response.getOutputStream());
                } finally { timeout.cancel(false); }
            }
        } catch (HttpTimeoutException e) {
            if (!response.isCommitted()) error(response, 504, "upstream_timeout", "Embeddings request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!response.isCommitted()) error(response, 502, "upstream_error", "Embeddings request interrupted");
        } catch (IOException e) {
            if (response.isCommitted()) throw e;
            error(response, 502, "upstream_error", "Embeddings response unavailable");
        } finally { embeddingSlots.release(); }
    }

    private static boolean validBody(byte[] body) {
        try {
            var json = JSON.readTree(body);
            if (json == null || !json.isObject() || !FIELDS.containsAll(json.propertyNames())) return false;
            return json.path("model").isString() && json.path("input").isString()
                && (!json.has("instructions") || json.path("instructions").isString())
                && (!json.has("max_output_tokens") || (json.path("max_output_tokens").isIntegralNumber()
                        && json.path("max_output_tokens").canConvertToInt() && json.path("max_output_tokens").asInt() > 0))
                && (!json.has("stream") || json.path("stream").isBoolean())
                && (!json.has("store") || (json.path("store").isBoolean() && !json.path("store").asBoolean()));
        } catch (RuntimeException e) { return false; }
    }

    private void relay(HttpServletRequest request, HttpServletResponse response, boolean post) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.matches("(?i:Bearer) cuvk_[A-Za-z0-9_-]{43}")) {
            error(response, 401, "invalid_api_key", "A direct virtual API key is required"); return;
        }
        if (request.getQueryString() != null) { error(response, 400, "invalid_request", "Query parameters are not supported"); return; }
        if (!slots.tryAcquire()) { error(response, 503, "overloaded", "Direct API is busy"); return; }
        try (var tracking = deployment.enter()) {
            String token = auth.substring(7);
            if (!keys.authorized(token)) { error(response, 401, "invalid_api_key", "Direct API key is invalid or inactive"); return; }
            if (request.getContentLengthLong() > MAX_BODY) { error(response, 413, "invalid_request", "Request is too large"); return; }
            byte[] body = post ? request.getInputStream().readNBytes(MAX_BODY + 1) : new byte[0];
            if (body.length > MAX_BODY) { error(response, 413, "invalid_request", "Request is too large"); return; }
            if (post && !validBody(body)) { error(response, 400, "invalid_request", "Only stateless text Responses requests are supported"); return; }
            var builder = HttpRequest.newBuilder(URI.create(broker + (post ? "/v1/responses" : "/v1/models")))
                    .timeout(Duration.ofSeconds(45)).header("Authorization", "Bearer " + token);
            if (post) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            var upstream = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = upstream.body()) {
                var timeout = deadlines.schedule(() -> { try { stream.close(); } catch (IOException ignored) { } }, 240, TimeUnit.SECONDS);
                try {
                    int status = upstream.statusCode();
                    if (status != 200) {
                        switch (status) {
                            case 400, 403, 404, 422 -> error(response, status, "request_rejected", "The broker or provider rejected this request. Check the model and request fields.");
                            case 401 -> error(response, 401, "invalid_api_key", "The direct API key could not be accepted");
                            case 429 -> error(response, 429, "limit_reached", "A rate, budget, or provider limit was reached");
                            default -> error(response, 502, "upstream_error", "Broker response unavailable");
                        }
                        return;
                    }
                    response.setStatus(status);
                    boolean streaming = upstream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream");
                    response.setContentType(streaming ? "text/event-stream" : "application/json");
                    for (String header : new String[]{"x-request-id", "x-broker-call-id", "retry-after"})
                        upstream.headers().firstValue(header).ifPresent(value -> response.setHeader(header, value));
                    var out = response.getOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int n; (n = stream.read(buffer)) != -1;) {
                        out.write(buffer, 0, n);
                        if (streaming) response.flushBuffer();
                    }
                } finally { timeout.cancel(false); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!response.isCommitted()) error(response, 502, "upstream_error", "Broker request interrupted");
        } catch (IOException e) {
            if (response.isCommitted()) throw e;
            error(response, 502, "upstream_error", "Broker response unavailable");
        } finally { slots.release(); }
    }

    private static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json");
        // Both values are fixed literals supplied by the call sites above.
        response.getOutputStream().print("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }
}
