package dev.subscriptionmeter.routing;

import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** Which OpenAI model stands in for a requested Claude model, and how much reasoning to ask for. */
public record ModelMapping(String opus, String sonnet, String haiku) {
    /** Explicit OpenAI model names (chosen by the member) pass through unchanged. */
    public static boolean openAIModel(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        return m.startsWith("gpt-") || m.matches("o\\d.*") || m.startsWith("codex-");
    }

    public String upstream(String requested) {
        if (openAIModel(requested)) return requested;
        String m = requested.toLowerCase(Locale.ROOT);
        if (m.contains("haiku")) return haiku;
        if (m.contains("opus")) return opus;
        return sonnet; // sonnet and unknown Claude names
    }

    /** Claude Code's extended thinking budget maps to a reasoning effort; small/helper models stay quick. */
    public String effort(String requested, JsonNode thinking) {
        if (upstream(requested).equals(haiku) && !openAIModel(requested)) return "low";
        if (thinking != null && "enabled".equals(thinking.path("type").asString(""))) {
            return thinking.path("budget_tokens").asInt(0) >= 16_000 ? "high" : "medium";
        }
        return "medium";
    }
}
