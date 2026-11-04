package dev.subscriptionmeter;

import dev.subscriptionmeter.messaging.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Run only against compose.audit-test.yaml; creates a unique topic and database schema. */
@EnabledIfEnvironmentVariable(named="AUDIT_INTEGRATION",matches="true")
class AuditKafkaIntegrationTest {
    private final AuditCodec codec=new AuditCodec();
    @Test void actualKafkaReplayAfterDatabaseCommitIsIdempotentAndLargePayloadSurvives()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","");
        String topic="audit-test-"+suffix,schema="audit_"+suffix,group="group-"+suffix;
        var base=new DriverManagerDataSource("jdbc:postgresql://localhost:25432/audit_test","audit_test","local-integration-only");
        var baseJdbc=new JdbcTemplate(base);baseJdbc.execute("CREATE SCHEMA "+schema);
        try(var admin=Admin.create(Map.of("bootstrap.servers","localhost:29092"))){
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get();
            AuditGroupBootstrap.initialize(admin,topic,group);
            assertThatThrownBy(()->AuditGroupBootstrap.initialize(admin,topic,group)).isInstanceOf(IllegalStateException.class);
            try{
                var ds=new DriverManagerDataSource("jdbc:postgresql://localhost:25432/audit_test?currentSchema="+schema,"audit_test","local-integration-only");
                var jdbc=new JdbcTemplate(ds);
                // Real PostgreSQL tables with the same projection schema; no production data.
                for(String sql:Files.readString(Path.of("src/main/resources/schema.sql")).split(";"))if(!sql.isBlank())jdbc.execute(sql);
                for(String sql:Files.readString(Path.of("deploy/migrations/V003__audit_pipeline.sql")).split(";"))if(!sql.isBlank())jdbc.execute(sql);
                var projector=new JdbcAuditProjector(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
                String request=UUID.randomUUID().toString(),time=Instant.now().toString();
                jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,active,rpm,concurrency_limit) VALUES('test-member','Integration','test-hash','test',TRUE,20,2)");
                jdbc.update("INSERT INTO requests(id,member_id,path,model,started_at) VALUES(?,'test-member','/v1/messages','integration-model',?)",request,java.sql.Timestamp.from(Instant.parse(time)));
                String payload="generated integration content 🌏 ".repeat(20000);
                Map<String,Object> begin=new HashMap<>(Map.of("started_at",time,"method","POST","path","/v1/messages","peer_address","localhost","protocol","HTTP/1.1","member_id","test-member","mode","subscription","headers","{}","body",payload));
                begin.put("query",null);
                var operation=new AuditOperation(request+":BEGIN",request,"BEGIN",time,begin);
                var event=new AuditOperation(request+":EVENT:1",request,"EVENT",time,Map.of("started_at",time,"sequence",1,
                        "elapsed_ms",25,"type","message_stop","data","{\"type\":\"message_stop\"}","truncated",false));
                var terminal=new HashMap<String,Object>();
                terminal.put("capture_state","complete");terminal.put("response_headers","{}");terminal.put("response_body","");
                terminal.put("response_bytes",31);terminal.put("captured_bytes",31);terminal.put("request_bytes",payload.length());
                terminal.put("event_count",1);terminal.put("truncated",false);terminal.put("upstream_headers_ms",10);terminal.put("first_byte_ms",20);
                terminal.put("error_type","");terminal.put("error_message","");
                var finish=new AuditOperation(request+":FINISH",request,"FINISH",time,terminal);
                var operations=List.of(operation,event,finish);
                var producerProperties=new Properties();producerProperties.put("bootstrap.servers","localhost:29092");producerProperties.put("acks","all");producerProperties.put("enable.idempotence",true);
                try(var producer=new KafkaProducer<String,byte[]>(producerProperties,new StringSerializer(),new ByteArraySerializer());
                    var publisher=new KafkaAuditPublisher(producer,topic,new SimpleMeterRegistry())){
                    publisher.publish(operations);
                    new KafkaRequestLedger(new dev.subscriptionmeter.persistence.JdbcRequestLedger(jdbc),publisher).finish(request,
                            new dev.subscriptionmeter.domain.RequestResult("complete",200,42,"integration-upstream",
                                    new dev.subscriptionmeter.domain.Usage(17,23,5,9,true,Map.of("input_tokens",17L,"output_tokens",23L,"cache_creation_input_tokens",5L,"cache_read_input_tokens",9L),true)));
                }
                int total=1;for(var op:operations)for(var unused:codec.split(op))total++;
                var unavailableDs=new DriverManagerDataSource("jdbc:postgresql://localhost:1/audit_test?connectTimeout=1&socketTimeout=1","audit_test","local-integration-only");
                var unavailable=new JdbcAuditProjector(new JdbcTemplate(unavailableDs),new TransactionTemplate(new DataSourceTransactionManager(unavailableDs)));
                // Broker already acknowledged RESULT. Projection database is unreachable for that event;
                // previously committed projections must replay safely when the consumer restarts.
                try(var consumer=consumer(group,topic)){
                    consume(consumer,total,projector,unavailable);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_details",Integer.class)).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT state FROM requests",String.class)).isEqualTo("pending");
                }
                try(var consumer=consumer(group,topic)){
                    consume(consumer,total,projector,null);consumer.commitSync();
                }
                assertThat(jdbc.queryForObject("SELECT request_body FROM request_details WHERE request_id=?",String.class,request)).isEqualTo(payload);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_details",Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_operations WHERE completed",Integer.class)).isEqualTo(4);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_events",Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT capture_state FROM request_details",String.class)).isEqualTo("complete");
                assertThat(jdbc.queryForObject("SELECT output_tokens FROM requests",Long.class)).isEqualTo(23);
                assertThat(jdbc.queryForObject("SELECT usage_complete FROM requests",Boolean.class)).isTrue();
                assertThat(jdbc.queryForObject("SELECT usage_components FROM requests",String.class)).contains("cache_creation_input_tokens");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_payload_parts",Integer.class)).isZero();
                // A projection exception rolls back its inbox row and remains retryable, never acknowledged as complete.
                var invalid=new AuditOperation(request+":BAD",request,"BAD",time,Map.of());
                AuditEnvelope bad=codec.split(invalid).iterator().next();
                assertThatThrownBy(()->projector.accept(bad)).isInstanceOf(RuntimeException.class);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_operations WHERE operation_id=?",Integer.class,bad.operationId())).isZero();
                var expired=new AuditOperation("expired:BEGIN","expired","BEGIN",Instant.now().minus(Duration.ofDays(31)).toString(),begin);
                for(var part:codec.split(expired))projector.accept(part);
                for(var part:codec.split(expired))projector.accept(part);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_details WHERE request_id='expired'",Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT discard_reason FROM audit_operations WHERE operation_id='expired:BEGIN'",String.class)).isEqualTo("retention_expired");
                var incomplete=new AuditOperation("incomplete:BEGIN","incomplete","BEGIN",time,begin);
                var incompleteParts=new ArrayList<AuditEnvelope>();codec.split(incomplete).forEach(incompleteParts::add);
                projector.accept(incompleteParts.getFirst());
                jdbc.update("UPDATE audit_operations SET observed_at=? WHERE operation_id='incomplete:BEGIN'",java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(31))));
                projector.expireIncomplete();
                for(var part:incompleteParts)projector.accept(part);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_payload_parts WHERE operation_id='incomplete:BEGIN'",Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_details WHERE request_id='incomplete'",Integer.class)).isZero();
            }finally{admin.deleteTopics(List.of(topic)).all().get();}
        }finally{baseJdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
    private KafkaConsumer<String,byte[]> consumer(String group,String topic){
        Properties p=new Properties();p.put("bootstrap.servers","localhost:29092");p.put("group.id",group);
        p.put("enable.auto.commit",false);p.put("auto.offset.reset","none");
        var c=new KafkaConsumer<String,byte[]>(p,new StringDeserializer(),new ByteArrayDeserializer());c.subscribe(List.of(topic));return c;
    }
    private void consume(KafkaConsumer<String,byte[]> consumer,int expected,JdbcAuditProjector projector,JdbcAuditProjector unavailable){
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();int count=0;
        while(count<expected && System.nanoTime()<deadline){
            for(var record:consumer.poll(Duration.ofMillis(250))){
                var envelope=codec.decode(record.value());
                if(unavailable!=null && envelope.kind().equals("RESULT"))assertThatThrownBy(()->unavailable.accept(envelope)).isInstanceOf(RuntimeException.class);
                else projector.accept(envelope);
                count++;
            }
        }
        assertThat(count).isEqualTo(expected);
    }
}
