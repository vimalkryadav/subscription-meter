package dev.subscriptionmeter.messaging;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.config.ConfigResource;
import java.util.*;
import java.util.concurrent.*;

/** Fail-closed cached probes plus a bounded write reservation between disk probes. */
public final class KafkaAuditSafetyGuard implements AutoCloseable {
    public enum Reason { WARMING_UP,READY,TOPIC_PARTITIONS,RETENTION_POLICY,CONSUMER_WATERMARK,BACKLOG_LIMIT,DISK_HEADROOM,LOG_BUDGET,DEPENDENCY_UNAVAILABLE }
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(KafkaAuditSafetyGuard.class);
    private static final long BURST_BYTES=64L*1024*1024,MIN_FREE=2L*1024*1024*1024,MAX_LOG=1024L*1024*1024;
    private final Admin admin;
    private final String topic,group;
    private final int partitions;
    private final ScheduledExecutorService probes=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).factory());
    private long checkedAt,reserved;
    private boolean safe;
    private volatile Reason reason=Reason.WARMING_UP;
    public KafkaAuditSafetyGuard(Admin admin,String topic,String group,int partitions){
        this.admin=admin;this.topic=topic;this.group=group;this.partitions=partitions;
        probes.scheduleWithFixedDelay(this::probe,0,250,TimeUnit.MILLISECONDS);
    }
    public synchronized boolean ready(){return safe&&System.nanoTime()-checkedAt<TimeUnit.SECONDS.toNanos(2);}
    public Reason reason(){return reason;}
    private static void require(boolean condition,Reason reason){if(!condition)throw new GuardFailure(reason);}
    private static final class GuardFailure extends RuntimeException {final Reason reason;GuardFailure(Reason reason){this.reason=reason;}}
    private synchronized void failed(Reason next){safe=false;if(reason!=next)LOG.warn("Audit admission unavailable: {}",next);reason=next;}
    public synchronized boolean reserve(long bytes){
        if(!ready()||bytes<0||bytes>BURST_BYTES-reserved)return false;
        reserved+=bytes;return true;
    }
    private <T>T get(KafkaFuture<T> value)throws Exception{return value.get(2,TimeUnit.SECONDS);}
    private void probe(){
        try{
            var description=get(admin.describeTopics(List.of(topic)).allTopicNames()).get(topic);
            require(description.partitions().size()==partitions,Reason.TOPIC_PARTITIONS);
            Set<Integer> brokers=new HashSet<>();Map<TopicPartition,OffsetSpec> starts=new HashMap<>(),ends=new HashMap<>();
            for(var partition:description.partitions()){
                require(partition.leader()!=null&&partition.leader().id()>=0,Reason.TOPIC_PARTITIONS);
                brokers.add(partition.leader().id());var tp=new TopicPartition(topic,partition.partition());
                starts.put(tp,OffsetSpec.earliest());ends.put(tp,OffsetSpec.latest());
            }
            var resource=new ConfigResource(ConfigResource.Type.TOPIC,topic);
            var config=get(admin.describeConfigs(List.of(resource)).all()).get(resource);
            require("-1".equals(config.get("retention.ms").value())&&"-1".equals(config.get("retention.bytes").value())
                    &&"delete".equals(config.get("cleanup.policy").value()),Reason.RETENTION_POLICY);
            var committed=get(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata());
            var earliest=get(admin.listOffsets(starts).all());var latest=get(admin.listOffsets(ends).all());
            long backlog=0;
            for(var tp:starts.keySet()){
                var offset=committed.get(tp);
                require(offset!=null&&offset.offset()>=earliest.get(tp).offset()&&offset.offset()<=latest.get(tp).offset(),Reason.CONSUMER_WATERMARK);
                backlog+=latest.get(tp).offset()-offset.offset();
            }
            require(backlog<=4096,Reason.BACKLOG_LIMIT);
            var directories=get(admin.describeLogDirs(brokers).allDescriptions());long bytes=0;
            for(var broker:directories.values())for(var directory:broker.values()){
                require(directory.error()==null&&directory.usableBytes().orElse(0)>=MIN_FREE,Reason.DISK_HEADROOM);
                for(var replica:directory.replicaInfos().values())bytes+=replica.size();
            }
            // Headroom for two gateway colors and requests already in an acknowledgement window.
            require(bytes<=MAX_LOG-4*BURST_BYTES,Reason.LOG_BUDGET);
            synchronized(this){safe=true;reason=Reason.READY;checkedAt=System.nanoTime();reserved=0;}
        }catch(InterruptedException e){Thread.currentThread().interrupt();failed(Reason.DEPENDENCY_UNAVAILABLE);}
        catch(GuardFailure e){failed(e.reason);}
        catch(Exception e){failed(Reason.DEPENDENCY_UNAVAILABLE);}
    }
    public void close(){probes.shutdownNow();}
}
