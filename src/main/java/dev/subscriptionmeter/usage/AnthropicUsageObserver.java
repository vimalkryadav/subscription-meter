package dev.subscriptionmeter.usage;

import dev.subscriptionmeter.domain.Usage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Incremental observer; the forwarding path never reconstructs SSE frames. */
public final class AnthropicUsageObserver implements UsageObserver {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_EVENT = 2 * 1024 * 1024;
    private final boolean streaming;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder event = new StringBuilder();
    private long input, output, creation, read;
    private boolean seen, stopped, error, malformed;
    public AnthropicUsageObserver(boolean streaming) { this.streaming = streaming; }
    public void accept(byte[] bytes, int length) {
        if (!streaming) {
            if (line.size() + length > 32 * 1024 * 1024) { malformed = true; return; }
            line.write(bytes, 0, length); return;
        }
        for (int i = 0; i < length; i++) {
            if (bytes[i] == '\n') {
                String text = line.toString(StandardCharsets.UTF_8); line.reset();
                if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
                if (text.startsWith("data:")) event.append(text.substring(5).stripLeading()).append('\n');
                else if (text.isEmpty() && !event.isEmpty()) { parseEvent(event.toString()); event.setLength(0); }
            } else line.write(bytes[i]);
            if (line.size() + event.length() > MAX_EVENT) { malformed = true; line.reset(); event.setLength(0); }
        }
    }
    public void finishJson() {
        try { update(JSON.readTree(line.toByteArray()).path("usage")); stopped = true; }
        catch (RuntimeException e) { malformed = true; }
    }
    private void parseEvent(String text) {
        try {
            JsonNode root = JSON.readTree(text);
            switch (root.path("type").asString()) {
                case "message_start" -> update(root.path("message").path("usage"));
                case "message_delta" -> update(root.path("usage"));
                case "message_stop" -> stopped = true;
                case "error" -> error = true;
                default -> { }
            }
        } catch (RuntimeException e) { malformed = true; }
    }
    private long value(JsonNode usage, String name, long prior) {
        JsonNode value = usage.path(name);
        if (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0) {
            seen = true; return Math.max(prior, value.asLong());
        }
        return prior;
    }
    private void update(JsonNode usage) {
        input = value(usage, "input_tokens", input);
        output = value(usage, "output_tokens", output);
        creation = value(usage, "cache_creation_input_tokens", creation);
        read = value(usage, "cache_read_input_tokens", read);
    }
    public Usage snapshot() { return new Usage(input, output, creation, read, seen); }
    public boolean complete() { return seen && stopped && !malformed && !error; }
    public boolean error() { return error; }
}
