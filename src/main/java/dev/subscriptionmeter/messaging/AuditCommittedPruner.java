package dev.subscriptionmeter.messaging;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit maintenance: the canonical worker's committed offsets are the hard deletion ceiling. */
public final class AuditCommittedPruner {
    private AuditCommittedPruner(){ }
    public static void prune(Admin admin,String topic,String group,Duration keepHistory)throws Exception{
        if(keepHistory.isNegative())throw new IllegalArgumentException("Invalid history retention");
        var description=admin.describeTopics(List.of(topic)).allTopicNames().get(5,TimeUnit.SECONDS).get(topic);
        var committed=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);
        Map<TopicPartition,OffsetSpec> starts=new HashMap<>(),ends=new HashMap<>(),history=new HashMap<>();
        for(var p:description.partitions()){
            var tp=new TopicPartition(topic,p.partition());starts.put(tp,OffsetSpec.earliest());ends.put(tp,OffsetSpec.latest());
            history.put(tp,OffsetSpec.forTimestamp(Instant.now().minus(keepHistory).toEpochMilli()));
        }
        var earliest=admin.listOffsets(starts).all().get(5,TimeUnit.SECONDS);
        var latest=admin.listOffsets(ends).all().get(5,TimeUnit.SECONDS);
        var boundary=keepHistory.isZero()?latest:admin.listOffsets(history).all().get(5,TimeUnit.SECONDS);
        Map<TopicPartition,RecordsToDelete> delete=new HashMap<>();
        for(var tp:starts.keySet()){
            var offset=committed.get(tp);
            if(offset==null||offset.offset()<earliest.get(tp).offset()||offset.offset()>latest.get(tp).offset())throw new IllegalStateException("Unsafe or missing consumer watermark");
            long historyOffset=boundary.get(tp).offset();
            if(historyOffset<0)historyOffset=latest.get(tp).offset();
            long before=Math.min(offset.offset(),historyOffset);
            if(before>earliest.get(tp).offset())delete.put(tp,RecordsToDelete.beforeOffset(before));
        }
        if(!delete.isEmpty())admin.deleteRecords(delete).all().get(10,TimeUnit.SECONDS);
    }
}
