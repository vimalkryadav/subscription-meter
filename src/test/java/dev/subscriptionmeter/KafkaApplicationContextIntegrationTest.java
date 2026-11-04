package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.AuditStore;
import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.messaging.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Boots the actual conditional Kafka bean graph and servlet filters against isolated real dependencies. */
@EnabledIfEnvironmentVariable(named="AUDIT_INTEGRATION",matches="true")
class KafkaApplicationContextIntegrationTest {
    @Test void kafkaApplicationStartsWithProxiedPrimaryLedgerAndJournalsRejectedAccessAndResults()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","");
        String schema="context_"+suffix,topic="context-test-"+suffix,group="context-group-"+suffix;
        var base=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:25432/audit_test","audit_test","local-integration-only"));
        Path secrets=Files.createTempDirectory("kafka-context-secrets-");
        String deployToken="integration-deploy-"+UUID.randomUUID();
        for(String file:List.of("admin","metrics"))Files.writeString(secrets.resolve(file),"integration-only-"+UUID.randomUUID());
        Files.writeString(secrets.resolve("deploy"),deployToken);Files.writeString(secrets.resolve("upstream"),"");
        base.execute("CREATE SCHEMA "+schema);
        try(var admin=Admin.create(Map.of("bootstrap.servers","localhost:29092"))){
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1).configs(Map.of("retention.ms","-1","retention.bytes","-1","cleanup.policy","delete")))).all().get();
            try{
                AuditGroupBootstrap.initialize(admin,topic,group);
                String databaseUrl="jdbc:postgresql://localhost:25432/audit_test?currentSchema="+schema;
                var setup=new JdbcTemplate(new DriverManagerDataSource(databaseUrl,"audit_test","local-integration-only"));
                for(String file:List.of("src/main/resources/schema.sql","src/main/resources/accounting-schema.sql","deploy/migrations/V003__audit_pipeline.sql"))
                    for(String sql:Files.readString(Path.of(file)).split(";"))if(!sql.isBlank())setup.execute(sql);
                try(var context=new SpringApplicationBuilder(MeterApplication.class).run(
                        "--server.port=0","--spring.datasource.url="+databaseUrl,"--spring.datasource.username=audit_test",
                        "--spring.datasource.password=local-integration-only","--spring.sql.init.mode=never",
                        "--meter.audit.timescale-enabled=false","--meter.audit.transport=kafka",
                        "--meter.audit.kafka.bootstrap-servers=localhost:29092","--meter.audit.kafka.topic="+topic,
                        "--meter.audit.kafka.group="+group,"--meter.audit.kafka.partitions=1","--meter.openai.enabled=false",
                        "--meter.admin-token-file="+secrets.resolve("admin"),"--meter.metrics-token-file="+secrets.resolve("metrics"),
                        "--meter.deploy-token-file="+secrets.resolve("deploy"),"--meter.upstream-token-file="+secrets.resolve("upstream"),
                        "--meter.mode=subscription","--meter.upstream-url=https://api.anthropic.com",
                        "--meter.release="+"a".repeat(40))){
                    RequestLedger ledger=context.getBean(RequestLedger.class);
                    assertThat(AopUtils.isAopProxy(ledger)).isTrue();
                    assertThat(AopUtils.getTargetClass(ledger)).isEqualTo(KafkaRequestLedger.class);
                    assertThat(ledger).isSameAs(context.getBean(KafkaRequestLedger.class));
                    AuditStore audit=context.getBean(AuditStore.class);
                    assertThat(AopUtils.getTargetClass(audit)).isEqualTo(KafkaAuditStore.class);
                    long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
                    while(!audit.ready()&&System.nanoTime()<deadline)Thread.sleep(50);
                    assertThat(audit.ready()).isTrue();
                    String origin="http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port");
                    try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()){
                        var readiness=http.send(HttpRequest.newBuilder(URI.create(origin+"/internal/deployment"))
                                .header("Authorization","Bearer "+deployToken).GET().build(),HttpResponse.BodyHandlers.ofString());
                        assertThat(readiness.statusCode()).isEqualTo(200);
                        assertThat(JsonMapper.builder().build().readTree(readiness.body()).path("ready").asBoolean()).isTrue();
                        assertThat(http.send(HttpRequest.newBuilder(URI.create(origin+"/")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
                        assertThat(http.send(HttpRequest.newBuilder(URI.create(origin+"/manage")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(303);
                        var rejected=http.send(HttpRequest.newBuilder(URI.create(origin+"/v1/messages"))
                                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding());
                        assertThat(rejected.statusCode()).isEqualTo(401); // Never reaches a provider.
                    }
                    var jdbc=context.getBean(JdbcTemplate.class);String request=UUID.randomUUID().toString();
                    jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,active,rpm,concurrency_limit) VALUES('context-member','Context test','context-hash','test',TRUE,20,2)");
                    ledger.begin(request,"context-member","/v1/messages","integration-model",Instant.now());
                    ledger.identify(request,"anthropic","primary","unknown");
                    ledger.finish(request,new RequestResult("complete",200,20,"context-upstream",new Usage(3,5,0,0,true,Map.of("input_tokens",3L,"output_tokens",5L),true)));
                    assertThat(jdbc.queryForObject("SELECT state FROM requests WHERE id=?",String.class,request)).isEqualTo("pending");
                    var projector=new JdbcAuditProjector(jdbc,new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))));
                    var codec=new AuditCodec();boolean resultSeen=false,accessSeen=false;
                    try(var consumer=new KafkaConsumer<String,byte[]>(Map.of("bootstrap.servers","localhost:29092","group.id",group,"enable.auto.commit",false,"auto.offset.reset","none"),new StringDeserializer(),new ByteArrayDeserializer())){
                        consumer.subscribe(List.of(topic));deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
                        while(!(resultSeen&&accessSeen)&&System.nanoTime()<deadline){
                            var batch=new ArrayList<AuditEnvelope>();
                            for(var record:consumer.poll(Duration.ofMillis(100))){var envelope=codec.decode(record.value());batch.add(envelope);resultSeen|=envelope.kind().equals("RESULT");accessSeen|=envelope.kind().equals("ACCESS");}
                            if(!batch.isEmpty()){projector.acceptBatch(batch);consumer.commitSync();}
                        }
                    }
                    assertThat(resultSeen&&accessSeen).isTrue();
                    assertThat(jdbc.queryForObject("SELECT output_tokens FROM requests WHERE id=?",Long.class,request)).isEqualTo(5);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_access WHERE status_code=401",Integer.class)).isEqualTo(1);
                }
            }finally{admin.deleteTopics(List.of(topic)).all().get();}
        }finally{
            base.execute("DROP SCHEMA "+schema+" CASCADE");
            try(var files=Files.list(secrets)){for(Path file:files.toList())Files.delete(file);}Files.delete(secrets);
        }
    }
}
