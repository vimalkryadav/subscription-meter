package dev.subscriptionmeter.routing;

import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Translates an Anthropic Messages request (as sent by Claude Code) into an OpenAI Responses request.
 * Client-executed tools become function tools; tool use and results become function_call items.
 * Thinking blocks (provider-signed) and server-side Anthropic tools are not carried over.
 */
public final class AnthropicToResponses {
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(16 * 1024 * 1024)
                    .maxStringLength(16 * 1024 * 1024).maxNestingDepth(256).build()).build()).build();
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Translated request plus the information needed to translate the reply back. */
    public record Translation(ObjectNode body, String requestedModel, String upstreamModel, boolean clientStreaming,
                              Map<String, String> toolNames) {}

    public static final class Unsupported extends Exception { Unsupported(String m) { super(m); } }

    private AnthropicToResponses() {}

    /** Tool names OpenAI rejects are replaced by a stable safe alias; the alias map restores them in replies. */
    static String safeName(String name, Map<String, String> aliases) {
        if (SAFE_NAME.matcher(name).matches()) return name;
        String base = name.replaceAll("[^A-Za-z0-9_-]", "_");
        String hash = Integer.toHexString(name.hashCode() & 0x7fffffff);
        String alias = (base.length() > 55 ? base.substring(0, 55) : base) + "_" + hash;
        aliases.put(alias, name);
        return alias;
    }

    private static String text(JsonNode content) {
        if (content.isString()) return content.asString();
        var out = new StringBuilder();
        for (JsonNode part : content) if ("text".equals(part.path("type").asString(""))) {
            if (!out.isEmpty()) out.append("\n\n");
            out.append(part.path("text").asString(""));
        }
        return out.toString();
    }

    private static ObjectNode image(JsonNode block) {
        JsonNode source = block.path("source");
        String url = switch (source.path("type").asString("")) {
            case "base64" -> "data:" + source.path("media_type").asString("image/png") + ";base64," + source.path("data").asString("");
            case "url" -> source.path("url").asString("");
            default -> "";
        };
        if (url.isEmpty()) return null;
        var node = JSON.createObjectNode();
        node.put("type", "input_image");
        node.put("image_url", url);
        return node;
    }

    private static ObjectNode message(String role, ArrayNode content) {
        var node = JSON.createObjectNode();
        node.put("type", "message");
        node.put("role", role);
        node.set("content", content);
        return node;
    }

    private static ObjectNode part(String type, String text) {
        var node = JSON.createObjectNode();
        node.put("type", type);
        node.put("text", text);
        return node;
    }

    public static Translation translate(byte[] raw, ModelMapping mapping, String promptCacheKey, boolean includeMaxTokens) throws Unsupported {
        JsonNode root;
        try { root = JSON.readTree(raw); } catch (RuntimeException e) { throw new Unsupported("Request body must be JSON"); }
        if (!root.isObject() || !root.path("messages").isArray()) throw new Unsupported("Anthropic messages request required");
        String requested = root.path("model").asString("");
        String upstream = mapping.upstream(requested);
        var aliases = new LinkedHashMap<String, String>();
        var body = JSON.createObjectNode();
        body.put("model", upstream);

        String system = root.has("system") ? text(root.path("system")) : "";
        if (!system.isBlank()) body.put("instructions", system);

        var input = body.putArray("input");
        for (JsonNode message : root.path("messages")) {
            String role = message.path("role").asString("");
            JsonNode content = message.path("content");
            if (role.equals("system")) {
                // Mid-conversation system instructions (newer Claude Code) become Responses developer messages.
                String text = text(content);
                if (!text.isBlank()) input.add(message("developer", JSON.createArrayNode().add(part("input_text", text))));
                continue;
            }
            if (!role.equals("user") && !role.equals("assistant")) throw new Unsupported("Unsupported message role: " + role);
            if (content.isString()) {
                if (!content.asString().isEmpty())
                    input.add(message(role, JSON.createArrayNode().add(part(role.equals("user") ? "input_text" : "output_text", content.asString()))));
                continue;
            }
            var parts = JSON.createArrayNode();
            var imagesFromResults = JSON.createArrayNode();
            for (JsonNode block : content) {
                switch (block.path("type").asString("")) {
                    case "text" -> {
                        String text = block.path("text").asString("");
                        if (!text.isEmpty()) parts.add(part(role.equals("user") ? "input_text" : "output_text", text));
                    }
                    case "image" -> { var img = image(block); if (img != null && role.equals("user")) parts.add(img); }
                    case "document" -> {
                        String title = block.path("title").asString("document");
                        String data = block.path("source").path("type").asString("").equals("text") ? block.path("source").path("data").asString("") : "";
                        parts.add(part("input_text", data.isEmpty() ? "[Attached " + title + " not available to this model]" : data));
                    }
                    case "tool_use", "server_tool_use" -> {
                        if (!parts.isEmpty()) { input.add(message(role, parts)); parts = JSON.createArrayNode(); }
                        var call = JSON.createObjectNode();
                        call.put("type", "function_call");
                        call.put("call_id", block.path("id").asString(""));
                        call.put("name", safeName(block.path("name").asString("tool"), aliases));
                        call.put("arguments", JSON.writeValueAsString(block.path("input").isMissingNode() ? JSON.createObjectNode() : block.path("input")));
                        input.add(call);
                    }
                    case "tool_result" -> {
                        if (!parts.isEmpty()) { input.add(message(role, parts)); parts = JSON.createArrayNode(); }
                        JsonNode result = block.path("content");
                        String output = result.isMissingNode() || result.isNull() ? "" : text(result);
                        if (result.isArray()) for (JsonNode item : result)
                            if ("image".equals(item.path("type").asString(""))) { var img = image(item); if (img != null) imagesFromResults.add(img); }
                        if (block.path("is_error").asBoolean(false)) output = "Error: " + output;
                        var out = JSON.createObjectNode();
                        out.put("type", "function_call_output");
                        out.put("call_id", block.path("tool_use_id").asString(""));
                        out.put("output", output.isEmpty() ? "(no output)" : output);
                        input.add(out);
                    }
                    default -> { } // thinking/redacted_thinking are provider-signed; web search results are Anthropic-only
                }
            }
            if (!imagesFromResults.isEmpty()) {
                imagesFromResults.insert(0, part("input_text", "Images returned by the tool calls above:"));
                input.add(message("user", imagesFromResults));
            }
            if (!parts.isEmpty()) input.add(message(role, parts));
        }

        var tools = JSON.createArrayNode();
        for (JsonNode tool : root.path("tools")) {
            if (!tool.has("input_schema")) continue; // Anthropic server tools (web search, code execution) have no OpenAI equivalent here
            var fn = JSON.createObjectNode();
            fn.put("type", "function");
            fn.put("name", safeName(tool.path("name").asString("tool"), aliases));
            if (tool.has("description")) fn.put("description", tool.path("description").asString(""));
            fn.set("parameters", tool.path("input_schema"));
            fn.put("strict", false);
            tools.add(fn);
        }
        if (!tools.isEmpty()) body.set("tools", tools);
        JsonNode choice = root.path("tool_choice");
        if (choice.isObject() && !tools.isEmpty()) {
            switch (choice.path("type").asString("auto")) {
                case "any" -> body.put("tool_choice", "required");
                case "none" -> body.put("tool_choice", "none");
                case "tool" -> {
                    var fn = JSON.createObjectNode();
                    fn.put("type", "function");
                    fn.put("name", safeName(choice.path("name").asString(""), aliases));
                    body.set("tool_choice", fn);
                }
                default -> body.put("tool_choice", "auto");
            }
            if (choice.path("disable_parallel_tool_use").asBoolean(false)) body.put("parallel_tool_calls", false);
        }

        var reasoning = body.putObject("reasoning");
        reasoning.put("effort", mapping.effort(requested, root.path("thinking")));

        if (includeMaxTokens && root.path("max_tokens").isIntegralNumber()) body.put("max_output_tokens", root.path("max_tokens").asInt());
        body.put("stream", true);
        body.put("store", false);
        if (promptCacheKey != null) body.put("prompt_cache_key", promptCacheKey);
        // Reverse map: upstream alias -> original name (identity entries omitted).
        return new Translation(body, requested, upstream, root.path("stream").asBoolean(false), aliases);
    }
}
