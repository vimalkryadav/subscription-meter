package dev.subscriptionmeter;

import dev.subscriptionmeter.persistence.JdbcMetricsReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class MetricsReaderTest {
    JdbcTemplate jdbc;
    JdbcMetricsReader reader;
    final Instant from=Instant.parse("2026-01-01T00:00:00Z"), to=from.plusSeconds(3600);
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:metrics"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE","sa","");
        jdbc=new JdbcTemplate(ds);
        // Keep one connection open while schema and queries execute through separate JdbcTemplate calls.
        ds.setUrl(ds.getUrl()+";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES('a','Alice','hash-a','sm_a',60,4)");
        reader=new JdbcMetricsReader(jdbc);
    }
    void request(String id,Instant time,String mode,int status,String state,long duration) {
        jdbc.update("""
            INSERT INTO requests(id,member_id,started_at,path,model,state,status_code,duration_ms,usage_seen,
              input_tokens,output_tokens,cache_creation_input_tokens,cache_read_input_tokens)
            VALUES(?,'a',?,'/v1/messages','model-a',?,?,?,TRUE,10,20,30,40)
            """,id,Timestamp.from(time),state,status,duration);
        if(mode!=null)jdbc.update("""
            INSERT INTO request_details(request_id,started_at,member_id,upstream_mode,method,path,first_byte_ms)
            VALUES(?,?,'a',?,'POST','/v1/messages',?)
            """,id,Timestamp.from(time),mode,duration/2);
    }
    @Test void windowModePercentilesAndCacheTokensAreAccurate() {
        request("mock",from,"mock",200,"complete",100);
        request("real-error",from.plusSeconds(10),"subscription",429,"failed",300);
        request("before",from.minusSeconds(1),"mock",200,"complete",900);
        request("at-end",to,"mock",200,"complete",900);
        var all=reader.read(from,to,60,"all");
        assertThat(((Number)all.summary().get("requests")).intValue()).isEqualTo(2);
        assertThat(((Number)all.summary().get("tokens")).longValue()).isEqualTo(200);
        assertThat(((Number)all.summary().get("duration_p50")).doubleValue()).isEqualTo(200);
        assertThat(((Number)all.summary().get("duration_p95")).doubleValue()).isCloseTo(290,within(.001));
        assertThat(((Number)all.summary().get("failed")).intValue()).isEqualTo(1);
        assertThat(all.traffic()).hasSize(1);
        assertThat(all.errors()).hasSize(1).first().satisfies(row->assertThat(row.get("code")).isEqualTo("429"));
        assertThat(((Number)all.timings().getFirst().get("first_byte_p50")).doubleValue()).isEqualTo(100);
        assertThat(all.topMembers().getFirst().get("label")).isEqualTo("Alice");
        var subscription=reader.read(from,to,60,"subscription");
        assertThat(((Number)subscription.summary().get("requests")).intValue()).isEqualTo(1);
        assertThat(((Number)subscription.summary().get("duration_p95")).doubleValue()).isEqualTo(300);
    }
    @Test void emptyAndMissingCaptureRemainHonestAndPendingIsNotFailure() {
        var empty=reader.read(from,to,60,"all");
        assertThat(((Number)empty.summary().get("requests")).intValue()).isZero();
        assertThat(empty.summary().get("duration_p95")).isNull();
        assertThat(empty.traffic()).isEmpty();
        request("metadata-only",from,null,200,"pending",0);
        jdbc.update("UPDATE requests SET duration_ms=NULL,usage_seen=FALSE WHERE id='metadata-only'");
        var all=reader.read(from,to,60,"all");
        assertThat(((Number)all.summary().get("pending")).intValue()).isEqualTo(1);
        assertThat(((Number)all.summary().get("failed")).intValue()).isZero();
        assertThat(((Number)all.summary().get("metadata_only")).intValue()).isEqualTo(1);
        assertThat(((Number)all.summary().get("usage_observed")).intValue()).isZero();
        assertThat(all.summary().get("duration_p95")).isNull();
        assertThat(reader.read(from,to,60,"mock").traffic()).isEmpty();
    }
}
