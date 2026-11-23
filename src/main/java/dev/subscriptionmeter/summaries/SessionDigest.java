package dev.subscriptionmeter.summaries;

import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds a compact, bounded transcript of a Claude Code conversation for summarizing:
 * the member's prompts, short assistant replies and the names/targets of tools used.
 * System prompts, tool definitions, tool outputs and file contents are left out.
 */
public final class SessionDigest {
    public static final int MAX_BODY_CHARS = 4 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_BODY_CHARS)
                    .maxStringLength(MAX_BODY_CHARS).maxNestingDepth(64).maxTokenCount(500_000).build()).build()).build();
    private static final Pattern REMINDER = Pattern.compile("<system-reminder>.*?</system-reminder>", Pattern.DOTALL);
    private static final Pattern WORKDIR = Pattern.compile("[Ww]orking directory: *([^\\s]+)");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final List<String> TOOL_TARGETS = List.of("file_path", "path", "pattern", "command", "description", "url", "query", "prompt");

    private SessionDigest() {}

    static String clip(String text, int max) {
        String flat = SPACE.matcher(text).replaceAll(" ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** Empty when the body is not a conversation or contains no member-written text. */
    public static Optional<String> build(String body, int maxChars) {
        if (body == null || body.length() > MAX_BODY_CHARS) return Optional.empty();
        JsonNode root;
        try { root = JSON.readTree(body); } catch (RuntimeException e) { return Optional.empty(); }
        if (!root.path("messages").isArray()) return Optional.empty();
        var lines = new ArrayList<String>();
        String project = project(root.path("system"));
        if (project != null) lines.add("PROJECT: " + project);
        boolean userText = false;
        for (JsonNode message : root.path("messages")) {
            String role = message.path("role").asString("");
            JsonNode content = message.path("content");
            if (content.isString()) {
                String text = clean(content.asString());
                if (!text.isEmpty()) { lines.add(label(role) + clip(text, "user".equals(role) ? 1500 : 300)); userText |= "user".equals(role); }
                continue;
            }
            for (JsonNode part : content) {
                switch (part.path("type").asString("")) {
                    case "text" -> {
                        String text = clean(part.path("text").asString(""));
                        if (!text.isEmpty()) { lines.add(label(role) + clip(text, "user".equals(role) ? 1500 : 300)); userText |= "user".equals(role); }
                    }
                    case "tool_use" -> lines.add("TOOL: " + clip(part.path("name").asString("?") + target(part.path("input")), 200));
                    default -> { } // tool_result, images, thinking: outputs and private reasoning are not summarized
                }
            }
        }
        if (!userText) return Optional.empty();
        return Optional.of(bound(lines, maxChars));
    }

    private static String label(String role) { return "user".equals(role) ? "USER: " : "ASSISTANT: "; }

    private static String clean(String text) { return REMINDER.matcher(text).replaceAll("").strip(); }

    private static String target(JsonNode input) {
        for (String key : TOOL_TARGETS) {
            JsonNode value = input.path(key);
            if (value.isString() && !value.asString().isBlank()) return " " + value.asString();
        }
        return "";
    }

    private static String project(JsonNode system) {
        var text = new StringBuilder();
        if (system.isString()) text.append(system.asString());
        else for (JsonNode part : system) text.append(part.path("text").asString("")).append('\n');
        var match = WORKDIR.matcher(text);
        if (!match.find()) return null;
        String path = match.group(1).replaceAll("[/\\\\]+$", "");
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        return name.isBlank() ? null : clip(name, 100);
    }

    /** Keeps the start (what was asked) and the end (where it got to), dropping the middle. */
    static String bound(List<String> lines, int maxChars) {
        int total = lines.stream().mapToInt(line -> line.length() + 1).sum();
        if (total <= maxChars) return String.join("\n", lines);
        int headBudget = maxChars / 4, tailBudget = maxChars - headBudget - 60;
        var head = new ArrayList<String>();
        int used = 0, i = 0;
        for (; i < lines.size() && used + lines.get(i).length() + 1 <= headBudget; i++) { head.add(lines.get(i)); used += lines.get(i).length() + 1; }
        var tail = new ArrayDeque<String>();
        used = 0;
        int j = lines.size() - 1;
        for (; j >= i && used + lines.get(j).length() + 1 <= tailBudget; j--) { tail.addFirst(lines.get(j)); used += lines.get(j).length() + 1; }
        head.add("… [" + (j - i + 1) + " entries omitted] …");
        head.addAll(tail);
        return String.join("\n", head);
    }
}
