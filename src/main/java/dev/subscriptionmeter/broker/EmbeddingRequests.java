package dev.subscriptionmeter.broker;

import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Validates text-only embedding requests without rewriting or retaining their content. */
final class EmbeddingRequests {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final Set<String> MODELS = Set.of("text-embedding-3-small");
    private static final Set<String> FIELDS = Set.of("model", "input", "dimensions", "encoding_format", "user");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_BYTES).maxStringLength(MAX_BYTES).maxNestingDepth(8).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Checked(String model, int items) {}

    static Checked check(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw invalid();
        var root = JSON.readTree(bytes);
        if (root == null || !root.isObject() || !FIELDS.containsAll(root.propertyNames())
                || !root.path("model").isString() || !MODELS.contains(root.path("model").asString())) throw invalid();
        var input = root.path("input");
        if (!input.isString()) {
            if (!input.isArray() || input.isEmpty() || input.size() > 2048) throw invalid();
            for (var item : input) if (!item.isString() || item.asString().isEmpty()) throw invalid();
        } else if (input.asString().isEmpty()) throw invalid();
        if (root.has("dimensions") && (!root.path("dimensions").isIntegralNumber()
                || !root.path("dimensions").canConvertToInt() || root.path("dimensions").asInt() < 1)) throw invalid();
        if (root.has("encoding_format") && (!root.path("encoding_format").isString()
                || !Set.of("float", "base64").contains(root.path("encoding_format").asString()))) throw invalid();
        if (root.has("user") && !root.path("user").isString()) throw invalid();
        return new Checked(root.path("model").asString(), input.isString() ? 1 : input.size());
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid embeddings request"); }
}
