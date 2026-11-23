package dev.subscriptionmeter.summaries;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HttpResponsesClient implements ResponsesClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;

    public HttpResponsesClient(@Value("${meter.openai-api.base-url:https://api.openai.com}") String base) {
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/v1/responses");
    }

    static Map<String, Object> body(Request r) {
        var schema = Map.of("type", "object", "additionalProperties", false, "required", List.of("summary", "category"),
                "properties", Map.of("summary", Map.of("type", "string"),
                        "category", Map.of("type", "string", "enum", List.copyOf(new TreeSet<>(SessionSummarizer.CATEGORIES)))));
        var body = new LinkedHashMap<String, Object>();
        body.put("model", r.model());
        body.put("store", false);
        body.put("instructions", r.instructions());
        body.put("input", r.input());
        body.put("max_output_tokens", r.maxOutputTokens());
        body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "session_summary", "strict", true, "schema", schema)));
        return body;
    }

    static OpenAIApiBudget.Usage usage(JsonNode root) {
        JsonNode u = root.path("usage");
        return new OpenAIApiBudget.Usage(u.path("input_tokens").asLong(0),
                u.path("input_tokens_details").path("cached_tokens").asLong(0), u.path("output_tokens").asLong(0));
    }

    static Result parse(String raw) throws Unusable {
        JsonNode root;
        try { root = JSON.readTree(raw); } catch (RuntimeException e) { throw new Unusable("Response is not JSON", null); }
        var usage = usage(root);
        if (!"completed".equals(root.path("status").asString(""))) throw new Unusable("Response status " + root.path("status").asString("missing"), usage);
        var text = new StringBuilder();
        for (JsonNode item : root.path("output"))
            if ("message".equals(item.path("type").asString("")))
                for (JsonNode part : item.path("content"))
                    if ("output_text".equals(part.path("type").asString(""))) text.append(part.path("text").asString(""));
        if (text.isEmpty()) throw new Unusable("Response has no text", usage);
        return new Result(text.toString(), usage);
    }

    public Result create(Request r) throws Rejected, Unusable, IOException, InterruptedException {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + r.apiKey()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body(r)))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var in = response.body()) { bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1); }
        if (response.statusCode() != 200) {
            String code = "";
            try { code = JSON.readTree(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).path("error").path("code").asString(""); }
            catch (RuntimeException ignored) { }
            // Only the status and the provider's short error code are kept; never the message body.
            throw new Rejected(response.statusCode(), "HTTP " + response.statusCode() + (code.matches("[a-z_]{1,60}") ? " " + code : ""));
        }
        if (bytes.length > MAX_RESPONSE_BYTES) throw new Unusable("Response too large", null);
        return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }
}
