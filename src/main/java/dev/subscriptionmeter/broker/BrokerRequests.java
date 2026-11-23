package dev.subscriptionmeter.broker;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validation and rewriting of Responses API requests, and usage extraction from replies. */
public final class BrokerRequests {
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_BODY_BYTES)
                    .maxStringLength(MAX_BODY_BYTES).maxNestingDepth(128).build()).build()).build();
    /** Tools billed per use (not by tokens) would bypass budgets, so only client-executed tools pass. */
    static final Set<String> ALLOWED_TOOL_TYPES = Set.of("function", "custom");

    public record Checked(String model, boolean streaming, byte[] body, int maxOutputTokens, long estimatedInputTokens) {}
    public static final class Invalid extends Exception {
        private final String type;
        Invalid(String type, String message) { super(message); this.type = type; }
        public String type() { return type; }
    }

    private BrokerRequests() {}

    public static Checked check(byte[] raw, BrokerStore.Key key) throws Invalid {
        JsonNode root;
        try { root = JSON.readTree(raw); } catch (RuntimeException e) { throw new Invalid("invalid_json", "Request body must be a JSON object."); }
        if (!(root instanceof ObjectNode body)) throw new Invalid("invalid_json", "Request body must be a JSON object.");
        JsonNode modelNode = body.path("model");
        if (!modelNode.isString() || !modelNode.asString().matches("[A-Za-z0-9._:-]{1,100}"))
            throw new Invalid("invalid_model", "A model is required.");
        String model = modelNode.asString();
        if (!key.allows(model)) throw new Invalid("broker_model_not_allowed", "This key may not use model " + model + ".");
        if (body.path("background").asBoolean(false)) throw new Invalid("broker_background_not_allowed", "Background responses are not allowed.");
        if (body.has("previous_response_id") || body.has("conversation"))
            throw new Invalid("broker_stored_state_not_allowed", "Send the full input; stored conversation state is not used.");
        for (JsonNode tool : body.path("tools")) {
            String type = tool.path("type").asString("");
            if (!ALLOWED_TOOL_TYPES.contains(type)) throw new Invalid("broker_tool_not_allowed", "Tool type " + type + " is not allowed.");
        }
        int requested = body.path("max_output_tokens").isIntegralNumber() ? body.path("max_output_tokens").asInt() : key.maxOutputTokens();
        int maxOutput = Math.max(1, Math.min(requested, key.maxOutputTokens()));
        body.put("max_output_tokens", maxOutput);
        body.put("store", false); // Nothing is retained at OpenAI on the shared account.
        boolean streaming = body.path("stream").asBoolean(false);
        byte[] rewritten = JSON.writeValueAsBytes(body);
        // Conservative token estimate: roughly one token per 3 bytes of the request.
        return new Checked(model, streaming, rewritten, maxOutput, rewritten.length / 3 + 64);
    }

    public static BigDecimal estimate(BrokerStore.Price price, Checked c) {
        return BrokerStore.cost(price, new BrokerStore.Usage(c.estimatedInputTokens(), 0, c.maxOutputTokens()));
    }

    static BrokerStore.Usage usage(JsonNode response) {
        JsonNode u = response.path("usage");
        if (!u.isObject() || !u.path("input_tokens").isIntegralNumber() || !u.path("output_tokens").isIntegralNumber()) return null;
        return new BrokerStore.Usage(u.path("input_tokens").asLong(), u.path("input_tokens_details").path("cached_tokens").asLong(0),
                u.path("output_tokens").asLong());
    }

    /** Usage from a complete JSON response body, or null. */
    public static BrokerStore.Usage usageFromJson(byte[] body) {
        try { return usage(JSON.readTree(body)); } catch (RuntimeException e) { return null; }
    }

    /**
     * Incremental SSE scanner: feed it the bytes passed to the client; it keeps the last terminal
     * response event's usage. Lines longer than the limit are skipped, never buffered without bound.
     */
    public static final class StreamUsage {
        private static final int MAX_LINE = 4 * 1024 * 1024;
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        private boolean overflow;
        private BrokerStore.Usage usage;

        public void accept(byte[] bytes, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                byte b = bytes[i];
                if (b == '\n') { finishLine(); continue; }
                if (overflow) continue;
                if (line.size() >= MAX_LINE) { overflow = true; line.reset(); continue; }
                line.write(b);
            }
        }

        private void finishLine() {
            if (!overflow && line.size() > 6) {
                String text = line.toString(StandardCharsets.UTF_8).strip();
                if (text.startsWith("data:") && text.contains("\"usage\"")) {
                    try {
                        JsonNode event = JSON.readTree(text.substring(5).strip());
                        String type = event.path("type").asString("");
                        if (type.equals("response.completed") || type.equals("response.incomplete") || type.equals("response.failed")) {
                            var found = BrokerRequests.usage(event.path("response"));
                            if (found != null) usage = found;
                        }
                    } catch (RuntimeException ignored) { }
                }
            }
            line.reset(); overflow = false;
        }

        public BrokerStore.Usage usage() { return usage; }
    }
}
