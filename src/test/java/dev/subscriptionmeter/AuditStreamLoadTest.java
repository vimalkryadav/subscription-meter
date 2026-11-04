package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.*;
import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.gateway.*;
import dev.subscriptionmeter.limits.*;
import dev.subscriptionmeter.messaging.*;
import dev.subscriptionmeter.persistence.JdbcRequestLedger;
import dev.subscriptionmeter.upstream.UpstreamTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Generated streams in isolation, real Kafka and PostgreSQL, no provider/network inference. */
@EnabledIfEnvironmentVariable(named="AUDIT_INTEGRATION",matches="true")
class AuditStreamLoadTest {
    @ParameterizedTest @ValueSource(ints={1,5,20})
    void concurrentStreamsPreserveBytesAndUsage(int concurrency)throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");String topic="stream-test-"+suffix,schema="load_"+suffix;
        var base=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:25432/audit_test","audit_test","local-integration-only"));
        base.execute("CREATE SCHEMA "+schema);
        var ds=new com.zaxxer.hikari.HikariDataSource();ds.setJdbcUrl("jdbc:postgresql://localhost:25432/audit_test?currentSchema="+schema);
        ds.setUsername("audit_test");ds.setPassword("local-integration-only");ds.setMaximumPoolSize(2);ds.setMinimumIdle(1);ds.setConnectionTimeout(5000);
        var jdbc=new JdbcTemplate(ds);var metrics=new SimpleMeterRegistry();
        try(var admin=Admin.create(Map.of("bootstrap.servers","localhost:29092"))) {
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(15,TimeUnit.SECONDS);
            try {
                for(String file:List.of("src/main/resources/schema.sql","deploy/migrations/V003__audit_pipeline.sql"))
                    for(String sql:Files.readString(Path.of(file)).split(";"))if(!sql.isBlank())jdbc.execute(sql);
                jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit) VALUES('load','Load','local-load-hash','load',600,20)");
                var member=new Member("load","Load","load",true,600,20);
                var properties=new Properties();properties.put("bootstrap.servers","localhost:29092");properties.put("acks","all");properties.put("enable.idempotence",true);properties.put("linger.ms",5);properties.put("delivery.timeout.ms",12000);properties.put("request.timeout.ms",5000);
                try(var producer=new KafkaProducer<String,byte[]>(properties,new StringSerializer(),new ByteArraySerializer());
                    var publisher=new KafkaAuditPublisher(producer,topic,metrics);
                    var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                    var projector=new JdbcAuditProjector(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
                    var stop=new AtomicBoolean();
                    var consumerFuture=executor.submit(()->consume(topic,projector,stop));
                    try {
                        var store=new KafkaAuditStore(publisher,new JdbcAuditStore(jdbc));
                        var factory=new AuditCaptureFactory(store,new CredentialRedactor(),metrics,"subscription",8388608,10000);
                        var ledger=new KafkaRequestLedger(new JdbcRequestLedger(jdbc),publisher);
                        String payload="data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":100,\"output_tokens\":0,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}}\n\n"
                            +"data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"generated 🌏\"}}\n\n".repeat(50)
                            +"data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":100}}\n\ndata: {\"type\":\"message_stop\"}\n\n";
                        byte[] expected=payload.getBytes(StandardCharsets.UTF_8);
                        UpstreamTransport transport=(method,path,headers,body)->new UpstreamTransport.Reply(){
                            final InputStream input=new ByteArrayInputStream(expected){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(113,len));}};
                            public int status(){return 200;}public Map<String,List<String>> headers(){return Map.of("content-type",List.of("text/event-stream"));}
                            public InputStream body(){return input;}public void close()throws IOException{input.close();}
                        };
                        var gateway=new GatewayService(new InMemoryRequestLimiter(),transport,ledger,new GatewayMetrics(metrics),factory);
                        var barrier=new CountDownLatch(1);var durations=new CopyOnWriteArrayList<Long>();var firstBytes=new CopyOnWriteArrayList<Long>();
                        var tasks=new ArrayList<Future<?>>();
                        long runStart=System.nanoTime();
                        for(int i=0;i<concurrency;i++)tasks.add(executor.submit(()->{
                            barrier.await();long started=System.nanoTime();var output=new ByteArrayOutputStream();var first=new AtomicBoolean(true);
                            var context=new AuditContext(UUID.randomUUID().toString(),Instant.now(),"POST","/v1/messages",null,Map.of(),"localhost","HTTP/1.1");
                            gateway.forward(member,context,"integration-model",Map.of(),"{\"model\":\"integration-model\"}".getBytes(),new ResponseSink(){
                                public void start(int status,Map<String,String> headers){assertThat(status).isEqualTo(200);}
                                public void write(byte[] bytes,int length){if(first.compareAndSet(true,false))firstBytes.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));output.write(bytes,0,length);}
                            });
                            assertThat(output.toByteArray()).isEqualTo(expected);durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));return null;
                        }));
                        barrier.countDown();for(var task:tasks)task.get(90,TimeUnit.SECONDS);
                        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
                        while(jdbc.queryForObject("SELECT COUNT(*) FROM requests WHERE state='complete'",Integer.class)<concurrency && System.nanoTime()<deadline){
                            if(consumerFuture.isDone())consumerFuture.get();Thread.sleep(50);
                        }
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests WHERE state='complete' AND usage_complete",Integer.class)).isEqualTo(concurrency);
                        assertThat(jdbc.queryForObject("SELECT SUM(output_tokens) FROM requests",Long.class)).isEqualTo(concurrency*100L);
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_events",Integer.class)).isEqualTo(concurrency*53);
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_details WHERE capture_state='complete'",Integer.class)).isEqualTo(concurrency);
                        Collections.sort(firstBytes);Collections.sort(durations);
                        var report=Map.of("concurrency",concurrency,"events",concurrency*53,"firstForwardP50Ms",firstBytes.get((concurrency-1)/2),
                            "firstForwardP95Ms",firstBytes.get((int)Math.ceil(concurrency*.95)-1),"durationP95Ms",durations.get((int)Math.ceil(concurrency*.95)-1),
                            "allQueryableMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-runStart),"fixture","generated-local-real-kafka-postgres");
                        Files.createDirectories(Path.of("target/benchmarks"));
                        Files.writeString(Path.of("target/benchmarks/audit-streams-"+concurrency+".json"),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(report));
                        System.out.println("Audit stream benchmark: "+report);
                    } finally {stop.set(true);consumerFuture.get(15,TimeUnit.SECONDS);}
                }
            } finally {admin.deleteTopics(List.of(topic)).all().get(15,TimeUnit.SECONDS);}
        } finally {ds.close();base.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
    private void consume(String topic,JdbcAuditProjector projector,AtomicBoolean stop){
        var properties=new Properties();properties.put("bootstrap.servers","localhost:29092");properties.put("group.id",topic);properties.put("auto.offset.reset","earliest");properties.put("enable.auto.commit",false);properties.put("max.poll.records",16);
        try(var consumer=new KafkaConsumer<String,byte[]>(properties,new StringDeserializer(),new ByteArrayDeserializer())){
            consumer.subscribe(List.of(topic));var codec=new AuditCodec();
            while(!stop.get()){var records=consumer.poll(Duration.ofMillis(100));var batch=new ArrayList<AuditEnvelope>();for(var r:records)batch.add(codec.decode(r.value()));if(!batch.isEmpty()){projector.acceptBatch(batch);consumer.commitSync();}}
        }
    }
}
