package dev.subscriptionmeter;

import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.persistence.*;
import dev.subscriptionmeter.security.MemberKeyVault;
import dev.subscriptionmeter.security.MemberService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class PersistenceTest {
    @Test void revocationLedgerRecoveryAndAnalytics() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:meter;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var service = new MemberService(new JdbcMemberRepository(jdbc),MemberKeyVault.forSecret("v".repeat(32)));
        var key = service.create("Alice", 60, 4);
        assertThat(service.authenticate(key.key()).name()).isEqualTo("Alice");
        assertThat(jdbc.queryForObject("SELECT key_hash FROM members", String.class)).doesNotContain(key.key());
        assertThat(jdbc.queryForObject("SELECT key_sealed FROM members", String.class)).doesNotContain(key.key());
        assertThat(service.currentKey(key.member().id())).contains(key.key());
        assertThat(MemberKeyVault.forSecret("w".repeat(32)).open(key.member().id(),
                jdbc.queryForObject("SELECT key_sealed FROM members", String.class))).isEmpty();
        var ledger = new JdbcRequestLedger(jdbc);
        ledger.begin("one", key.member().id(), "/v1/messages", "mock");
        ledger.finish("one", new RequestResult("complete", 200, 12, "up", new Usage(120, 17, 30, 80, true)));
        ledger.begin("two", key.member().id(), "/v1/messages", "mock"); ledger.markInterrupted();
        var rows = new JdbcAnalyticsReader(jdbc).summarize(Instant.EPOCH);
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> { assertThat(row.get("state")).isEqualTo("complete"); assertThat(((Number) row.get("output_tokens")).longValue()).isEqualTo(17); });
        var rotated = service.rotate(key.member().id(), 60, 4, true);
        assertThat(service.currentKey(key.member().id())).contains(rotated.key());
        assertThatThrownBy(() -> service.authenticate(key.key())).hasMessageContaining("401");
        service.revoke(key.member().id());
        assertThat(service.currentKey(key.member().id())).isEmpty();
        assertThatThrownBy(() -> service.authenticate(rotated.key())).hasMessageContaining("401");
    }
}
