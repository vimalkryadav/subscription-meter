package dev.subscriptionmeter.routing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts an OpenAI Responses event stream into Anthropic Messages events, so Claude Code (and the
 * gateway's own usage accounting) see a normal Anthropic reply. Reasoning items are not exposed.
 */
public final class ResponsesToAnthropic {
    private final String model;
    private final Map<String, String> toolNames;
    private final Map<Integer, Block> blocks = new HashMap<>();
    private int nextIndex;
    private boolean started, finished, sawToolCall;
    private String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");

    private static final class Block { int index; boolean tool, open, sentContent; }

    public ResponsesToAnthropic(String requestedModel, Map<String, String> toolNames) {
        this.model = requestedModel; this.toolNames = toolNames;
    }

    public boolean finished() { return finished; }

    private ObjectNode event(String type) { var e = AnthropicToResponses.JSON.createObjectNode(); e.put("type", type); return e; }

    private void start(List<ObjectNode> out) {
        if (started) return;
        started = true;
        var e = event("message_start");
        var m = e.putObject("message");
        m.put("id", messageId); m.put("type", "message"); m.put("role", "assistant"); m.put("model", model);
        m.putArray("content"); m.putNull("stop_reason"); m.putNull("stop_sequence");
        var u = m.putObject("usage"); u.put("input_tokens", 0); u.put("output_tokens", 0);
        out.add(e);
    }

    private Block open(int outputIndex, boolean tool, String id, String name, List<ObjectNode> out) {
        Block existing = blocks.get(outputIndex);
        if (existing != null) return existing;
        var b = new Block(); b.index = nextIndex++; b.tool = tool; b.open = true;
        blocks.put(outputIndex, b);
        var e = event("content_block_start");
        e.put("index", b.index);
        var cb = e.putObject("content_block");
        if (tool) { cb.put("type", "tool_use"); cb.put("id", id); cb.put("name", toolNames.getOrDefault(name, name)); cb.putObject("input"); sawToolCall = true; }
        else { cb.put("type", "text"); cb.put("text", ""); }
        out.add(e);
        return b;
    }

    private void delta(Block b, String kind, String field, String value, List<ObjectNode> out) {
        if (value == null || value.isEmpty()) return;
        var e = event("content_block_delta");
        e.put("index", b.index);
        var d = e.putObject("delta"); d.put("type", kind); d.put(field, value);
        b.sentContent = true;
        out.add(e);
    }

    private void close(Block b, List<ObjectNode> out) {
        if (!b.open) return;
        b.open = false;
        var e = event("content_block_stop"); e.put("index", b.index);
        out.add(e);
    }

    private void finish(JsonNode response, String stopReason, List<ObjectNode> out) {
        blocks.values().stream().sorted(Comparator.comparingInt(b -> b.index)).forEach(b -> close(b, out));
        var e = event("message_delta");
        var d = e.putObject("delta"); d.put("stop_reason", stopReason); d.putNull("stop_sequence");
        JsonNode u = response.path("usage");
        long input = u.path("input_tokens").asLong(0), cached = u.path("input_tokens_details").path("cached_tokens").asLong(0);
        var usage = e.putObject("usage");
        usage.put("input_tokens", Math.max(0, input - cached));
        usage.put("output_tokens", u.path("output_tokens").asLong(0));
        usage.put("cache_read_input_tokens", cached);
        usage.put("cache_creation_input_tokens", 0);
        out.add(e);
        out.add(event("message_stop"));
        finished = true;
    }

    /** Anthropic error event (also used when the upstream stream ends early). */
    public ObjectNode error(String type, String message) {
        var e = event("error");
        var err = e.putObject("error"); err.put("type", type); err.put("message", message);
        finished = true;
        return e;
    }

    /** Converts one Responses event (the parsed JSON of an SSE data line). */
    public List<ObjectNode> accept(JsonNode ev) {
        var out = new ArrayList<ObjectNode>();
        if (finished) return out;
        String type = ev.path("type").asString("");
        start(out);
        int index = ev.path("output_index").asInt(-1);
        switch (type) {
            case "response.output_item.added" -> {
                JsonNode item = ev.path("item");
                switch (item.path("type").asString("")) {
                    case "message" -> open(index, false, null, null, out);
                    case "function_call" -> open(index, true, item.path("call_id").asString(""), item.path("name").asString(""), out);
                    default -> { }
                }
            }
            case "response.output_text.delta", "response.refusal.delta" ->
                delta(open(index, false, null, null, out), "text_delta", "text", ev.path("delta").asString(""), out);
            case "response.function_call_arguments.delta" -> {
                Block b = blocks.get(index);
                if (b != null && b.tool) delta(b, "input_json_delta", "partial_json", ev.path("delta").asString(""), out);
            }
            case "response.output_item.done" -> {
                JsonNode item = ev.path("item");
                String itemType = item.path("type").asString("");
                if (itemType.equals("function_call")) {
                    Block b = open(index, true, item.path("call_id").asString(""), item.path("name").asString(""), out);
                    if (!b.sentContent) delta(b, "input_json_delta", "partial_json", item.path("arguments").asString(""), out);
                    close(b, out);
                } else if (itemType.equals("message")) {
                    Block b = open(index, false, null, null, out);
                    if (!b.sentContent) for (JsonNode c : item.path("content"))
                        delta(b, "text_delta", "text", c.path("text").asString(c.path("refusal").asString("")), out);
                    close(b, out);
                }
            }
            case "response.completed" -> finish(ev.path("response"), sawToolCall ? "tool_use" : "end_turn", out);
            case "response.incomplete" -> {
                String reason = ev.path("response").path("incomplete_details").path("reason").asString("");
                finish(ev.path("response"), reason.equals("max_output_tokens") ? "max_tokens" : sawToolCall ? "tool_use" : "end_turn", out);
            }
            case "response.failed" -> out.add(error("api_error", "The fallback model failed: "
                    + ev.path("response").path("error").path("message").asString("unknown error")));
            case "error" -> out.add(error("api_error", "The fallback model failed: " + ev.path("message").asString(ev.path("error").path("message").asString("unknown error"))));
            default -> { }
        }
        return out;
    }

    static byte[] frame(ObjectNode event) {
        return ("event: " + event.path("type").asString() + "\ndata: " + AnthropicToResponses.JSON.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Lazily converts an upstream Responses SSE body into Anthropic SSE bytes. */
    public static InputStream streaming(InputStream upstream, ResponsesToAnthropic converter) {
        var reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8), 65536);
        return new InputStream() {
            private byte[] buffer = new byte[0];
            private int position;
            private boolean done;
            private final StringBuilder data = new StringBuilder();

            private boolean fill() throws IOException {
                while (position >= buffer.length) {
                    if (done) return false;
                    var pending = new ByteArrayOutputStream();
                    String line = reader.readLine();
                    if (line == null) {
                        done = true;
                        if (!converter.finished()) pending.writeBytes(frame(converter.error("api_error", "The fallback model's stream ended before completing.")));
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).stripLeading());
                        if (data.length() > 32 * 1024 * 1024) throw new IOException("Upstream event too large");
                        continue;
                    } else if (line.isEmpty() && !data.isEmpty()) {
                        String json = data.toString(); data.setLength(0);
                        if (!json.equals("[DONE]")) {
                            JsonNode ev;
                            try { ev = AnthropicToResponses.JSON.readTree(json); } catch (RuntimeException e) { continue; }
                            for (var out : converter.accept(ev)) pending.writeBytes(frame(out));
                        }
                    } else continue;
                    buffer = pending.toByteArray(); position = 0;
                }
                return true;
            }

            @Override public int read() throws IOException { return fill() ? buffer[position++] & 0xff : -1; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (!fill()) return -1;
                int n = Math.min(len, buffer.length - position);
                System.arraycopy(buffer, position, b, off, n);
                position += n;
                return n;
            }
            @Override public void close() throws IOException { upstream.close(); }
        };
    }

    /** Builds a complete Anthropic message (non-streaming client request) from the same events. */
    public static byte[] aggregate(InputStream upstream, ResponsesToAnthropic converter) throws IOException {
        var message = AnthropicToResponses.JSON.createObjectNode();
        var content = new TreeMap<Integer, ObjectNode>();
        var toolJson = new HashMap<Integer, StringBuilder>();
        ObjectNode error = null;
        try (InputStream events = streaming(upstream, converter);
             var reader = new BufferedReader(new InputStreamReader(events, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (!line.startsWith("data: ")) continue;
                var ev = (ObjectNode) AnthropicToResponses.JSON.readTree(line.substring(6));
                switch (ev.path("type").asString("")) {
                    case "message_start" -> message.setAll((ObjectNode) ev.path("message"));
                    case "content_block_start" -> content.put(ev.path("index").asInt(), (ObjectNode) ev.path("content_block").deepCopy());
                    case "content_block_delta" -> {
                        int i = ev.path("index").asInt();
                        JsonNode d = ev.path("delta");
                        if (d.path("type").asString("").equals("text_delta")) {
                            var block = content.get(i);
                            block.put("text", block.path("text").asString("") + d.path("text").asString(""));
                        } else toolJson.computeIfAbsent(i, k -> new StringBuilder()).append(d.path("partial_json").asString(""));
                    }
                    case "message_delta" -> {
                        message.put("stop_reason", ev.path("delta").path("stop_reason").asString("end_turn"));
                        message.set("usage", ev.path("usage"));
                    }
                    case "error" -> error = ev;
                    default -> { }
                }
            }
        }
        if (error != null) return AnthropicToResponses.JSON.writeValueAsBytes(error);
        toolJson.forEach((i, json) -> {
            JsonNode parsed;
            try { parsed = AnthropicToResponses.JSON.readTree(json.isEmpty() ? "{}" : json.toString()); }
            catch (RuntimeException e) { parsed = AnthropicToResponses.JSON.createObjectNode(); }
            content.get(i).set("input", parsed);
        });
        var array = message.putArray("content");
        content.values().forEach(array::add);
        return AnthropicToResponses.JSON.writeValueAsBytes(message);
    }
}
