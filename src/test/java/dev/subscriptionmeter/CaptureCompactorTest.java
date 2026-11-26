package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.CaptureCompactor;
import dev.subscriptionmeter.audit.CredentialRedactor;
import dev.subscriptionmeter.audit.JdbcAuditStore;
import dev.subscriptionmeter.domain.RequestQuery.CaptureSection;
import dev.subscriptionmeter.persistence.JdbcRequestQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class CaptureCompactorTest {
    JdbcTemplate jdbc; TransactionTemplate tx;
    final Instant old = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:compact-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("accounting-schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES('member','Example','hash','prefix',60,4)");
    }

    String body(int messages) {
        var sb = new StringBuilder("{\"model\":\"m\",\"system\":\"s\",\"messages\":[");
        for (int i = 0; i < messages; i++) sb.append(i > 0 ? "," : "").append("{\"role\":\"user\",\"content\":\"turn ").append(i).append("\"}");
        return new CredentialRedactor().payload(sb.append("]}").toString());
    }

    void capture(String id, Instant at, String body, String state) {
        jdbc.update("INSERT INTO requests(id,member_id,started_at,path,model,state,status_code,duration_ms,usage_seen) VALUES(?,'member',?,'/v1/messages','m','complete',200,1,TRUE)", id, Timestamp.from(at));
        jdbc.update("INSERT INTO request_details(request_id,started_at,member_id,upstream_mode,method,path,request_body,capture_state) VALUES(?,?,'member','subscription','POST','/v1/messages',?,?)",
                id, Timestamp.from(at), body, state);
    }

    CaptureCompactor compactor(String mode) { return new CaptureCompactor(jdbc, tx, new SimpleMeterRegistry(), mode, 20, 300); }

    @Test void shadowStoresAndVerifiesPiecesButKeepsEveryInlineBody() {
        capture("a", old, body(3), "complete");
        capture("b", old.plusSeconds(1), body(4), "complete");
        assertThat(compactor("shadow").runBatch()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_details WHERE request_body IS NOT NULL AND body_format=1 AND body_check=1", Integer.class)).isEqualTo(2);
        // 4 distinct messages + system, shared across both turns.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_blobs", Integer.class)).isEqualTo(5);
        assertThat(compactor("shadow").runBatch()).isZero();
    }

    @Test void compactClearsInlineBodiesOnlyAfterVerificationAndReadersStillSeeThem() {
        capture("a", old, body(5), "complete");
        compactor("shadow").runBatch();
        assertThat(compactor("compact").runBatch()).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT request_body,body_format,body_chars FROM request_details WHERE request_id='a'");
        assertThat(row.get("request_body")).isNull();
        assertThat(((Number) row.get("body_format")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("body_chars")).intValue()).isEqualTo(body(5).length());

        var query = new JdbcRequestQuery(jdbc, 0);
        assertThat(query.detail("a").orElseThrow().get("request_body")).isEqualTo(body(5));
        assertThat(query.detail("a").orElseThrow()).doesNotContainKeys("body_manifest", "body_format");
        assertThat(query.content("a", old, CaptureSection.INPUT).orElseThrow().body()).isEqualTo(body(5));
        assertThat(new JdbcAuditStore(jdbc).detail("a").orElseThrow().get("request_body")).isEqualTo(body(5));
    }

    @Test void pendingAndRecentCapturesAreLeftAlone() {
        capture("pending", old, body(2), "pending");
        capture("recent", Instant.now(), body(2), "complete");
        assertThat(compactor("compact").runBatch()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_details WHERE request_body IS NOT NULL AND body_format=1", Integer.class)).isEqualTo(2);
    }

    @Test void offDoesNothingAndUnknownModesAreRejected() {
        capture("a", old, body(2), "complete");
        assertThat(compactor("off").runBatch()).isZero();
        assertThatThrownBy(() -> compactor("delete")).isInstanceOf(IllegalArgumentException.class);
    }
}
