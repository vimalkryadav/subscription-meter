package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.*;
import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.persistence.*;
import dev.subscriptionmeter.security.MemberService;
import java.time.Instant;
import java.util.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class AuditPersistenceTest {
    @Test void fullDetailEventPaginationAndRejectedAccessAreQueryable() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc=new JdbcTemplate(ds);
        var member=new MemberService(new JdbcMemberRepository(jdbc)).create("Alice",60,4).member();
        var ledger=new JdbcRequestLedger(jdbc);
        var store=new JdbcAuditStore(jdbc);
        var context=new AuditContext("req",Instant.now(),"POST","/v1/messages",null,Map.of(),"127.0.0.1","HTTP/1.1");
        ledger.begin("req",member.id(),"/v1/messages","mock");
        var capture=new AuditCapture(store,new CredentialRedactor(),new SimpleMeterRegistry(),context,member.id(),"mock",
                "{\"messages\":[{\"content\":\"full prompt\"}]}".getBytes(),4096,100);
        capture.headers(Map.of("content-type",List.of("text/event-stream")),true);
        byte[] data="data: {\"type\":\"message_start\"}\n\ndata: {\"type\":\"message_stop\"}\n\n".getBytes();
        capture.bytes(data,data.length); capture.finish("complete");
        ledger.finish("req",new RequestResult("complete",200,100,"upstream",Usage.empty()));
        assertThat(store.list(Instant.EPOCH,member.id(),10,0)).hasSize(1);
        assertThat(store.detail("req").orElseThrow().get("request_body").toString()).contains("full prompt");
        assertThat(store.events("req",0,1)).hasSize(1);
        assertThat(store.events("req",1,100)).hasSize(1).first().satisfies(e->assertThat(e.get("sequence")).isEqualTo(2));
        store.access(context,null,401,1,"{}","");
        assertThat(store.accesses(Instant.EPOCH,10,0)).hasSize(1).first().satisfies(e->assertThat(e.get("status_code")).isEqualTo(401));
    }
}
