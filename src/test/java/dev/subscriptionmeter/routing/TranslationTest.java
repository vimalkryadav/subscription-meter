package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.usage.AnthropicUsageObserver;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

class TranslationTest {
    static final ModelMapping MAPPING = new ModelMapping("gpt-6.2-sol", "gpt-6.1-terra", "gpt-6-luna");

    static final String CLAUDE_CODE_REQUEST = """
        {"model":"claude-opus-5-5","max_tokens":32000,"stream":true,
         "system":[{"type":"text","text":"You are Claude Code."},{"type":"text","text":"Be concise.","cache_control":{"type":"ephemeral"}}],
         "thinking":{"type":"enabled","budget_tokens":31999},
         "tools":[{"name":"Edit","description":"Edit a file","input_schema":{"type":"object","properties":{"file_path":{"type":"string"}}}},
                  {"name":"mcp__srv__do.thing","description":"MCP","input_schema":{"type":"object"}},
                  {"type":"web_search_20250305","name":"web_search","max_uses":5}],
         "tool_choice":{"type":"any"},
         "messages":[
           {"role":"user","content":"Fix the test"},
           {"role":"assistant","content":[{"type":"thinking","thinking":"secret","signature":"sig"},{"type":"text","text":"Looking."},
               {"type":"tool_use","id":"toolu_1","name":"Edit","input":{"file_path":"a.java"}},
               {"type":"tool_use","id":"toolu_2","name":"mcp__srv__do.thing","input":{}}]},
           {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"done"},
               {"type":"tool_result","tool_use_id":"toolu_2","is_error":true,"content":[{"type":"text","text":"boom"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAA"}}]},
               {"type":"text","text":"Now run it"}]}]}""";

    @Test void translatesAClaudeCodeConversation() throws Exception {
        var t = AnthropicToResponses.translate(CLAUDE_CODE_REQUEST.getBytes(StandardCharsets.UTF_8), MAPPING, "session-1", true);
        var b = t.body();
        assertThat(t.upstreamModel()).isEqualTo("gpt-6.2-sol");
        assertThat(t.clientStreaming()).isTrue();
        assertThat(b.path("instructions").asString()).isEqualTo("You are Claude Code.\n\nBe concise.");
        assertThat(b.path("reasoning").path("effort").asString()).isEqualTo("high");
        assertThat(b.path("max_output_tokens").asInt()).isEqualTo(32000);
        assertThat(b.path("stream").asBoolean()).isTrue();
        assertThat(b.path("store").asBoolean()).isFalse();
        assertThat(b.path("prompt_cache_key").asString()).isEqualTo("session-1");
        assertThat(b.path("tool_choice").asString()).isEqualTo("required");
        var tools = b.path("tools");
        assertThat(tools).hasSize(2); // the Anthropic server tool is dropped
        String alias = tools.get(1).path("name").asString();
        assertThat(alias).matches("[A-Za-z0-9_-]{1,64}").isNotEqualTo("mcp__srv__do.thing");
        assertThat(t.toolNames()).containsEntry(alias, "mcp__srv__do.thing");
        var types = new ArrayList<String>();
        for (JsonNode item : b.path("input")) types.add(item.path("type").asString() + ":" + item.path("role").asString(""));
        assertThat(types).containsExactly("message:user", "message:assistant", "function_call:", "function_call:",
                "function_call_output:", "function_call_output:", "message:user", "message:user");
        JsonNode input = b.path("input");
        assertThat(input.get(1).path("content").get(0).path("type").asString()).isEqualTo("output_text");
        assertThat(input.get(2).path("arguments").asString()).isEqualTo("{\"file_path\":\"a.java\"}");
        assertThat(input.get(3).path("name").asString()).isEqualTo(alias);
        assertThat(input.get(5).path("output").asString()).isEqualTo("Error: boom");
        assertThat(input.get(6).path("content").get(1).path("image_url").asString()).isEqualTo("data:image/png;base64,AAA");
        assertThat(input.get(7).path("content").get(0).path("text").asString()).isEqualTo("Now run it");
        assertThat(b.toString()).doesNotContain("secret", "sig", "cache_control");
    }

    @Test void midConversationSystemMessagesBecomeDeveloperMessages() throws Exception {
        var t = AnthropicToResponses.translate(("{\"model\":\"claude-opus-5-5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"role\":\"system\",\"content\":[{\"type\":\"text\",\"text\":\"Plan mode is on.\"}]}]}").getBytes(), MAPPING, null, true);
        var item = t.body().path("input").get(1);
        assertThat(item.path("role").asString()).isEqualTo("developer");
        assertThat(item.path("content").get(0).path("text").asString()).isEqualTo("Plan mode is on.");
        assertThatThrownBy(() -> AnthropicToResponses.translate("{\"model\":\"x\",\"messages\":[{\"role\":\"tool\",\"content\":\"x\"}]}".getBytes(), MAPPING, null, true))
                .hasMessageContaining("tool");
    }

    @Test void mapsModelsAndEffort() throws Exception {
        assertThat(MAPPING.upstream("claude-haiku-5-5")).isEqualTo("gpt-6-luna");
        assertThat(MAPPING.upstream("claude-sonnet-5-5")).isEqualTo("gpt-6.1-terra");
        assertThat(MAPPING.upstream("claude-fable-5-2")).isEqualTo("gpt-6.1-terra");
        assertThat(MAPPING.upstream("gpt-6.2-astra")).isEqualTo("gpt-6.2-astra");
        assertThat(ModelMapping.openAIModel("o3")).isTrue();
        assertThat(ModelMapping.openAIModel("claude-opus-5-5")).isFalse();
        var t = AnthropicToResponses.translate("{\"model\":\"claude-haiku-5-5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":5}".getBytes(), MAPPING, null, false);
        assertThat(t.body().path("reasoning").path("effort").asString()).isEqualTo("low");
        assertThat(t.body().has("max_output_tokens")).isFalse();
        assertThat(t.body().has("prompt_cache_key")).isFalse();
        assertThatThrownBy(() -> AnthropicToResponses.translate("[]".getBytes(), MAPPING, null, true)).isInstanceOf(AnthropicToResponses.Unsupported.class);
    }

    static String responsesStream() {
        var events = List.of(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}",
            "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"reasoning\"}}",
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"reasoning\"}}",
            "{\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"message\"}}",
            "{\"type\":\"response.output_text.delta\",\"output_index\":1,\"delta\":\"Hel\"}",
            "{\"type\":\"response.output_text.delta\",\"output_index\":1,\"delta\":\"lo\"}",
            "{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]}}",
            "{\"type\":\"response.output_item.added\",\"output_index\":2,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_9\",\"name\":\"ALIAS\"}}",
            "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":2,\"delta\":\"{\\\"file_path\\\":\"}",
            "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":2,\"delta\":\"\\\"b.java\\\"}\"}",
            "{\"type\":\"response.output_item.done\",\"output_index\":2,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_9\",\"name\":\"ALIAS\",\"arguments\":\"{\\\"file_path\\\":\\\"b.java\\\"}\"}}",
            "{\"type\":\"response.output_item.added\",\"output_index\":3,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_10\",\"name\":\"Read\"}}",
            "{\"type\":\"response.output_item.done\",\"output_index\":3,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_10\",\"name\":\"Read\",\"arguments\":\"{}\"}}",
            "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1200,\"input_tokens_details\":{\"cached_tokens\":1000},\"output_tokens\":80}}}");
        var sse = new StringBuilder();
        for (String e : events) sse.append("event: x\ndata: ").append(e).append("\n\n");
        return sse.toString();
    }

    @Test void convertsAStreamThatTheGatewayUsageObserverAccepts() throws Exception {
        var converter = new ResponsesToAnthropic("claude-opus-5-5", Map.of("ALIAS", "mcp__srv__do.thing"));
        byte[] out = ResponsesToAnthropic.streaming(new ByteArrayInputStream(responsesStream().getBytes(StandardCharsets.UTF_8)), converter).readAllBytes();
        String text = new String(out, StandardCharsets.UTF_8);
        var types = new ArrayList<String>();
        for (String line : text.split("\n")) if (line.startsWith("event: ")) types.add(line.substring(7));
        assertThat(types).containsExactly("message_start", "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop");
        assertThat(text).contains("\"name\":\"mcp__srv__do.thing\"", "\"id\":\"call_9\"", "\"stop_reason\":\"tool_use\"", "\"model\":\"claude-opus-5-5\"");
        var observer = new AnthropicUsageObserver(true);
        observer.accept(out, out.length);
        var usage = observer.snapshot();
        assertThat(observer.complete()).isTrue();
        assertThat(observer.error()).isFalse();
        assertThat(usage.input()).isEqualTo(200);
        assertThat(usage.cacheRead()).isEqualTo(1000);
        assertThat(usage.output()).isEqualTo(80);
    }

    @Test void aggregatesForNonStreamingClients() throws Exception {
        var converter = new ResponsesToAnthropic("claude-haiku-5-5", Map.of());
        var json = AnthropicToResponses.JSON.readTree(ResponsesToAnthropic.aggregate(new ByteArrayInputStream(responsesStream().getBytes(StandardCharsets.UTF_8)), converter));
        assertThat(json.path("type").asString()).isEqualTo("message");
        assertThat(json.path("content").get(0).path("text").asString()).isEqualTo("Hello");
        assertThat(json.path("content").get(1).path("input").path("file_path").asString()).isEqualTo("b.java");
        assertThat(json.path("content").get(2).path("input").isObject()).isTrue();
        assertThat(json.path("stop_reason").asString()).isEqualTo("tool_use");
        assertThat(json.path("usage").path("output_tokens").asLong()).isEqualTo(80);
        var observer = new AnthropicUsageObserver(false);
        byte[] bytes = AnthropicToResponses.JSON.writeValueAsBytes(json);
        observer.accept(bytes, bytes.length); observer.finishJson();
        assertThat(observer.snapshot().output()).isEqualTo(80);
    }

    @Test void truncatedOrFailedStreamsBecomeAnthropicErrors() throws Exception {
        String cut = "data: {\"type\":\"response.created\"}\n\ndata: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"x\"}\n\n";
        String text = new String(ResponsesToAnthropic.streaming(new ByteArrayInputStream(cut.getBytes()), new ResponsesToAnthropic("m", Map.of())).readAllBytes());
        assertThat(text).contains("event: error", "ended before completing").doesNotContain("message_stop");
        String failed = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"usage limit\"}}}\n\n";
        String f = new String(ResponsesToAnthropic.streaming(new ByteArrayInputStream(failed.getBytes()), new ResponsesToAnthropic("m", Map.of())).readAllBytes());
        assertThat(f).contains("event: error", "usage limit");
        var observer = new AnthropicUsageObserver(true);
        observer.accept(f.getBytes(), f.length());
        assertThat(observer.error()).isTrue();
    }
}
