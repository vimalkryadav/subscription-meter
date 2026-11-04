package dev.subscriptionmeter.messaging;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit first-use action; refuses to reset an existing group's committed offsets. */
public final class AuditGroupBootstrap {
    private AuditGroupBootstrap(){ }
    public static void initialize(Admin admin,String topic,String group)throws Exception{
        var existing=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);
        if(!existing.isEmpty())throw new IllegalStateException("Consumer group already initialized; refusing reset");
        var description=admin.describeTopics(List.of(topic)).allTopicNames().get(5,TimeUnit.SECONDS).get(topic);
        Map<TopicPartition,OffsetSpec> query=new HashMap<>();
        for(var p:description.partitions())query.put(new TopicPartition(topic,p.partition()),OffsetSpec.earliest());
        var beginnings=admin.listOffsets(query).all().get(5,TimeUnit.SECONDS);
        Map<TopicPartition,OffsetAndMetadata> offsets=new HashMap<>();
        for(var entry:beginnings.entrySet()){
            if(entry.getValue().offset()!=0)throw new IllegalStateException("Nonzero log start requires explicit recovery review");
            offsets.put(entry.getKey(),new OffsetAndMetadata(0));
        }
        admin.alterConsumerGroupOffsets(group,offsets).all().get(5,TimeUnit.SECONDS);
    }
}
