package dev.subscriptionmeter.messaging;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent process: no gateway, web server, automatic schema mutation or provider credential. */
public final class AuditWorkerMain {
    public static void main(String[] args){
        try{run(args);}
        catch(Exception failure){System.err.println("Audit worker stopped: "+failure.getClass().getSimpleName()+"; inspect internal health and replay metadata");System.exit(1);}
    }
    private static void run(String[] args)throws Exception{
        if(args.length==1&&Set.of("--bootstrap-group","--prune-committed","--prune-committed-now").contains(args[0])){
            try(var admin=org.apache.kafka.clients.admin.Admin.create(Map.of("bootstrap.servers",required("METER_AUDIT_KAFKA_BOOTSTRAP_SERVERS")))){
                String topic=env("METER_AUDIT_KAFKA_TOPIC","control-usage.audit.v1"),group=env("METER_AUDIT_KAFKA_GROUP","control-usage-audit-v1");
                if(args[0].equals("--bootstrap-group"))AuditGroupBootstrap.initialize(admin,topic,group);
                else AuditCommittedPruner.prune(admin,topic,group,args[0].equals("--prune-committed-now")?Duration.ZERO:Duration.ofDays(1));
            }
            System.out.println("Audit maintenance completed using verified consumer offsets.");return;
        }
        if(args.length!=0)throw new IllegalArgumentException("Unsupported worker argument");
        String url=required("SPRING_DATASOURCE_URL");
        if(!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("Audit worker requires PostgreSQL");
        url+=(url.contains("?")?"&":"?")+"connectTimeout=5&socketTimeout=10&tcpKeepAlive=true";
        var pool=new com.zaxxer.hikari.HikariConfig();pool.setJdbcUrl(url);pool.setUsername(required("SPRING_DATASOURCE_USERNAME"));pool.setPassword(required("SPRING_DATASOURCE_PASSWORD"));
        pool.setMaximumPoolSize(2);pool.setMinimumIdle(1);pool.setConnectionTimeout(5000);pool.setValidationTimeout(2000);pool.setPoolName("audit-projection");
        var ds=new com.zaxxer.hikari.HikariDataSource(pool);
        var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(5);
        var projector=new JdbcAuditProjector(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),
                Integer.parseInt(env("AUDIT_RETENTION_DAYS","30")),Integer.parseInt(env("AUDIT_METADATA_RETENTION_DAYS","90")));
        Properties p=new Properties();p.put("bootstrap.servers",required("METER_AUDIT_KAFKA_BOOTSTRAP_SERVERS"));
        p.put("group.id",env("METER_AUDIT_KAFKA_GROUP","control-usage-audit-v1"));p.put("enable.auto.commit",false);
        p.put("auto.offset.reset","none");p.put("max.poll.records",16);p.put("max.partition.fetch.bytes",524288);
        p.put("fetch.max.bytes",4194304);p.put("allow.auto.create.topics",false);
        var stopped=new AtomicBoolean();
        try(var ownedDataSource=ds;
            var telemetry=new AuditWorkerTelemetry(env("METER_AUDIT_WORKER_BIND","127.0.0.1"),Integer.parseInt(env("METER_AUDIT_WORKER_PORT","9091")));
            var consumer=new KafkaConsumer<String,byte[]>(p,new StringDeserializer(),new ByteArrayDeserializer())){
            Runtime.getRuntime().addShutdownHook(new Thread(()->{stopped.set(true);consumer.wakeup();}));
            consumer.subscribe(List.of(env("METER_AUDIT_KAFKA_TOPIC","control-usage.audit.v1")));
            var codec=new AuditCodec();
            long lastHealth=0;
            while(!stopped.get()){
                var records=consumer.poll(Duration.ofSeconds(1));
                telemetry.polled(!consumer.assignment().isEmpty());
                var batch=new ArrayList<AuditEnvelope>();
                for(var record:records)batch.add(codec.decode(record.value()));
                if(!batch.isEmpty())projector.acceptBatch(batch);
                for(var record:records)telemetry.projected(record.value().length);
                if(!records.isEmpty())consumer.commitSync();
                if(System.nanoTime()-lastHealth>Duration.ofSeconds(10).toNanos()){
                    jdbc.queryForObject("SELECT 1",Integer.class);telemetry.databaseHealthy(true);
                    projector.expireIncomplete();
                    long lag=0;
                    for(var end:consumer.endOffsets(consumer.assignment(),Duration.ofSeconds(2)).entrySet())
                        lag+=Math.max(0,end.getValue()-consumer.position(end.getKey()));
                    telemetry.lag(lag);lastHealth=System.nanoTime();
                }
            }
        }catch(WakeupException e){if(!stopped.get())throw e;}
        // A poison record or database failure exits nonzero without offset commit. The supervisor
        // restarts with backoff; investigation/replay is required, never silently skip audit data.
    }
    private static String env(String name,String fallback){return System.getenv().getOrDefault(name,fallback);}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+name);return value;}
}
