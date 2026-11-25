package dev.subscriptionmeter.broker;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EmbeddingRequestsTest {
    private static EmbeddingRequests.Checked check(String fields) {
        return EmbeddingRequests.check(("{\"model\":\"text-embedding-3-small\"," + fields + "}").getBytes(StandardCharsets.UTF_8));
    }

    @Test void acceptsTextAndBatchesWithOptions() {
        assertThat(check("\"input\":\"hello\"").items()).isEqualTo(1);
        assertThat(check("\"input\":[\"hello\",\"world\"],\"dimensions\":1536,\"encoding_format\":\"base64\",\"user\":\"example\"").items()).isEqualTo(2);
        assertThat(check("\"input\":[" + String.join(",", Collections.nCopies(2048, "\"x\"")) + "]").items()).isEqualTo(2048);
    }

    @Test void rejectsUnsupportedModelsAndFields() {
        for (String fields : new String[]{"\"input\":[]", "\"input\":[1]", "\"input\":\"x\",\"dimensions\":1.5",
                "\"input\":\"x\",\"encoding_format\":\"hex\"", "\"input\":\"x\",\"background\":true",
                "\"input\":[" + String.join(",", Collections.nCopies(2049, "\"x\"")) + "]"})
            assertThatThrownBy(() -> check(fields)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingRequests.check("{\"model\":\"text-embedding-3-large\",\"input\":\"x\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
