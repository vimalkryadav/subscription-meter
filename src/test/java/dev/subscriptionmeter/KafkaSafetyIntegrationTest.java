package dev.subscriptionmeter;

import dev.subscriptionmeter.messaging.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="AUDIT_INTEGRATION",matches="true")
class KafkaSafetyIntegrationTest {
    @Test void readinessRejectsUnsafeRetentionAndLostOffsetsThenRecoversAfterCorrection()throws Exception{
        String topic="safety-test-"+UUID.randomUUID(),group="group-"+UUID.randomUUID();
        try(var admin=Admin.create(Map.of("bootstrap.servers","localhost:29092"))){
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1).configs(Map.of("retention.ms","-1","retention.bytes","-1")))).all().get();
            try{
                AuditGroupBootstrap.initialize(admin,topic,group);
                try(var guard=new KafkaAuditSafetyGuard(admin,topic,group,1)){
                    await(guard::ready);assertThat(guard.reserve(1024)).isTrue();assertThat(guard.reserve(65L*1024*1024)).isFalse();
                    var resource=new ConfigResource(ConfigResource.Type.TOPIC,topic);
                    admin.incrementalAlterConfigs(Map.of(resource,List.of(new AlterConfigOp(new ConfigEntry("retention.ms","86400000"),AlterConfigOp.OpType.SET)))).all().get();
                    await(()->!guard.ready());
                    admin.incrementalAlterConfigs(Map.of(resource,List.of(new AlterConfigOp(new ConfigEntry("retention.ms","-1"),AlterConfigOp.OpType.SET)))).all().get();
                    await(guard::ready);
                    var partition=new TopicPartition(topic,0);
                    admin.alterConsumerGroupOffsets(group,Map.of(partition,new OffsetAndMetadata(100))).all().get();
                    await(()->!guard.ready());
                    admin.alterConsumerGroupOffsets(group,Map.of(partition,new OffsetAndMetadata(0))).all().get();
                    await(guard::ready);
                    try(var producer=new KafkaProducer<String,byte[]>(Map.of("bootstrap.servers","localhost:29092"),new StringSerializer(),new ByteArraySerializer())){
                        producer.send(new ProducerRecord<>(topic,0,"test",new byte[]{1})).get();
                        producer.send(new ProducerRecord<>(topic,0,"test",new byte[]{2})).get();
                    }
                    admin.alterConsumerGroupOffsets(group,Map.of(partition,new OffsetAndMetadata(1))).all().get();
                    AuditCommittedPruner.prune(admin,topic,group,Duration.ZERO);
                    assertThat(admin.listOffsets(Map.of(partition,OffsetSpec.earliest())).all().get().get(partition).offset()).isEqualTo(1);
                    assertThat(admin.listOffsets(Map.of(partition,OffsetSpec.latest())).all().get().get(partition).offset()).isEqualTo(2);
                    await(guard::ready); // unprojected offset 1 survives the explicit prune.
                    admin.alterConsumerGroupOffsets(group,Map.of(partition,new OffsetAndMetadata(0))).all().get();
                    await(()->!guard.ready()); // committed zero is now below retained log start one.
                    assertThatThrownBy(()->AuditGroupBootstrap.initialize(admin,topic,"new-"+group)).isInstanceOf(IllegalStateException.class);
                }
            }finally{admin.deleteTopics(List.of(topic)).all().get();}
        }
    }
    private void await(BooleanSupplier predicate)throws InterruptedException{
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(!predicate.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(50);
        assertThat(predicate.getAsBoolean()).isTrue();
    }
}
