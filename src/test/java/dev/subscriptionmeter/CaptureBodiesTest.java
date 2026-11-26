package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.CaptureBodies;
import dev.subscriptionmeter.audit.CredentialRedactor;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CaptureBodiesTest {
    final CredentialRedactor redactor = new CredentialRedactor();

    String turn(int messages, int cacheAt) {
        var sb = new StringBuilder("{\"model\":\"claude-opus-5-5\",\"max_tokens\":32000,");
        sb.append("\"system\":[{\"type\":\"text\",\"text\":\"You are Claude Code.\",\"cache_control\":{\"type\":\"ephemeral\"}}],");
        sb.append("\"tools\":[{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}],\"messages\":[");
        for (int i = 0; i < messages; i++) {
            if (i > 0) sb.append(',');
            String role = i % 2 == 0 ? "user" : "assistant";
            String marker = i == cacheAt ? ",\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}" : "";
            sb.append("{\"role\":\"").append(role).append("\",\"content\":[{\"type\":\"text\",\"text\":\"message ").append(i)
              .append(" \\u00e9\\ud83d\\ude00 1.5e-7\"").append(marker).append("},{\"type\":\"tool_result\",\"content\":\"x\",\"n\":1.0}]}");
        }
        // The stored body is always the redactor's re-serialized JSON.
        return redactor.payload(sb.append("],\"stream\":true,\"temperature\":0.7}").toString());
    }

    Map<String, String> store(CaptureBodies.Split split) {
        return split.pieces().stream().collect(Collectors.toMap(CaptureBodies.Piece::key, CaptureBodies.Piece::data));
    }

    @Test void rebuildsExactlyIncludingMovedCacheMarkers() {
        String body = turn(6, 5);
        var split = CaptureBodies.split("member", body);
        assertThat(CaptureBodies.rebuild(split.manifest(), store(split))).isEqualTo(body);
        assertThat(split.chars()).isEqualTo(body.codePointCount(0, body.length()));
    }

    @Test void consecutiveTurnsShareEverythingButTheNewMessages() {
        var first = CaptureBodies.split("member", turn(40, 39));
        var second = CaptureBodies.split("member", turn(42, 41)); // marker moved to the newest message
        Set<String> known = store(first).keySet();
        var fresh = second.pieces().stream().filter(p -> !known.contains(p.key())).toList();
        assertThat(fresh).hasSize(2);
        Map<String, String> all = new HashMap<>(store(first));
        all.putAll(store(second));
        assertThat(CaptureBodies.rebuild(second.manifest(), all)).isEqualTo(turn(42, 41));
    }

    @Test void piecesAreSeparatePerMember() {
        var a = CaptureBodies.split("member-a", turn(2, 1));
        var b = CaptureBodies.split("member-b", turn(2, 1));
        assertThat(store(a).keySet()).doesNotContainAnyElementsOf(store(b).keySet());
    }

    @Test void nonObjectBodiesAreOneWholePiece() {
        for (String body : List.of("not json [REDACTED]", "[1,2,3]", "")) {
            var split = CaptureBodies.split("member", body);
            assertThat(split.pieces()).hasSize(1);
            assertThat(CaptureBodies.rebuild(split.manifest(), store(split))).isEqualTo(body);
        }
    }

    @Test void otherEndpointsKeepUnknownFieldsInTheManifest() {
        String body = redactor.payload("{\"model\":\"gpt-6-luna\",\"input\":[{\"role\":\"user\",\"content\":\"hi\"}],\"metadata\":{\"a\":null},\"n\":12345678901234}");
        var split = CaptureBodies.split("member", body);
        assertThat(CaptureBodies.rebuild(split.manifest(), store(split))).isEqualTo(body);
    }

    @Test void missingPieceFailsInsteadOfReturningAPartialBody() {
        var split = CaptureBodies.split("member", turn(3, 2));
        var pieces = new HashMap<>(store(split));
        pieces.remove(split.pieces().getLast().key());
        assertThatThrownBy(() -> CaptureBodies.rebuild(split.manifest(), pieces)).isInstanceOf(IllegalStateException.class);
    }
}
