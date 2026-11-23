package dev.subscriptionmeter.summaries;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HttpResponsesClientTest {
    @Test void requestIsStatelessAndSchemaConstrained() {
        var body = HttpResponsesClient.body(new ResponsesClient.Request("sk-x", "gpt-6-luna", "rules", "transcript", 1500));
        assertThat(body).containsEntry("model", "gpt-6-luna").containsEntry("store", false).containsEntry("max_output_tokens", 1500)
                .containsEntry("instructions", "rules").containsEntry("input", "transcript").doesNotContainKey("apiKey");
        assertThat(body.toString()).doesNotContain("sk-x").contains("json_schema", "debugging");
    }

    @Test void parsesTextAndUsageAndRejectsIncompleteResponses() throws Exception {
        var result = HttpResponsesClient.parse("""
            {"status":"completed","output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"{\\"summary\\":\\"ok\\"}"}]}],
             "usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":200},"output_tokens":90}}
            """);
        assertThat(result.text()).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(result.usage()).isEqualTo(new OpenAIApiBudget.Usage(1200, 200, 90));
        assertThatThrownBy(() -> HttpResponsesClient.parse("{\"status\":\"incomplete\",\"usage\":{\"input_tokens\":5,\"output_tokens\":1500}}"))
                .isInstanceOfSatisfying(ResponsesClient.Unusable.class, e -> assertThat(e.usage().output()).isEqualTo(1500));
        assertThatThrownBy(() -> HttpResponsesClient.parse("<html>")).isInstanceOf(ResponsesClient.Unusable.class);
    }
}
