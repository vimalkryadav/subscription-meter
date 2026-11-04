package dev.subscriptionmeter.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.serialization.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.util.Properties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(name="meter.audit.transport",havingValue="kafka")
public class KafkaAuditConfiguration {
    @Bean(destroyMethod="close") Admin auditKafkaAdmin(@Value("${meter.audit.kafka.bootstrap-servers:localhost:9092}")String servers){
        return Admin.create(Map.of("bootstrap.servers",servers,"default.api.timeout.ms",2000,"request.timeout.ms",1500));
    }
    @Bean(destroyMethod="close") KafkaAuditSafetyGuard auditSafetyGuard(Admin auditKafkaAdmin,
            @Value("${meter.audit.kafka.topic:control-usage.audit.v1}")String topic,
            @Value("${meter.audit.kafka.group:control-usage-audit-v1}")String group,
            @Value("${meter.audit.kafka.partitions:1}")int partitions,MeterRegistry metrics){
        var guard=new KafkaAuditSafetyGuard(auditKafkaAdmin,topic,group,partitions);
        metrics.gauge("meter.audit.guard.ready",guard,g->g.ready()?1:0);
        for(var reason:KafkaAuditSafetyGuard.Reason.values())metrics.gauge("meter.audit.guard.reason",io.micrometer.core.instrument.Tags.of("reason",reason.name()),guard,g->g.reason()==reason?1:0);
        return guard;
    }
    @Bean(destroyMethod="close") KafkaAuditPublisher auditPublisher(
            @Value("${meter.audit.kafka.bootstrap-servers:localhost:9092}")String servers,
            @Value("${meter.audit.kafka.topic:control-usage.audit.v1}")String topic,
            @Value("${meter.audit.kafka.partitions:1}")int partitions,MeterRegistry metrics,KafkaAuditSafetyGuard guard){
        Properties p=new Properties();
        p.put("bootstrap.servers",servers);p.put("acks","all");p.put("enable.idempotence",true);
        p.put("max.in.flight.requests.per.connection",5);p.put("delivery.timeout.ms",12000);
        p.put("request.timeout.ms",5000);p.put("max.block.ms",3000);p.put("linger.ms",5);
        p.put("buffer.memory",33554432L);p.put("max.request.size",524288);p.put("compression.type","lz4");
        return new KafkaAuditPublisher(new KafkaProducer<>(p,new StringSerializer(),new ByteArraySerializer()),topic,metrics,partitions,guard::ready,guard::reserve);
    }
}
