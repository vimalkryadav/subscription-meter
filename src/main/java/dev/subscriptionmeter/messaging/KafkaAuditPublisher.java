package dev.subscriptionmeter.messaging;

import dev.subscriptionmeter.audit.AuditUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class KafkaAuditPublisher implements AuditPublisher, AutoCloseable {
    private final Producer<String,byte[]> producer;
    private final String topic;
    private final AuditCodec codec=new AuditCodec();
    private final MeterRegistry metrics;
    private final java.util.function.BooleanSupplier readiness;
    private final int partitions;
    private final java.util.function.LongPredicate capacity;
    private final AuditMemoryBudget memory=new AuditMemoryBudget();
    private final io.micrometer.core.instrument.Timer totalTimer,memoryTimer,brokerWaitTimer;
    private io.micrometer.core.instrument.Timer timer(String name){
        return io.micrometer.core.instrument.Timer.builder(name).serviceLevelObjectives(
            java.util.stream.LongStream.of(1,5,10,25,50,100,250,500,1000,5000,15000,20000).mapToObj(Duration::ofMillis).toArray(Duration[]::new)).register(metrics);
    }
    public KafkaAuditPublisher(Producer<String,byte[]> producer,String topic,MeterRegistry metrics){
        this(producer,topic,metrics,1,()->true,bytes->true);
    }
    public KafkaAuditPublisher(Producer<String,byte[]> producer,String topic,MeterRegistry metrics,int partitions,java.util.function.BooleanSupplier readiness){
        this(producer,topic,metrics,partitions,readiness,bytes->true);
    }
    public KafkaAuditPublisher(Producer<String,byte[]> producer,String topic,MeterRegistry metrics,int partitions,java.util.function.BooleanSupplier readiness,java.util.function.LongPredicate capacity){
        if(partitions<1)throw new IllegalArgumentException("Invalid audit partition count");
        this.producer=producer;this.topic=topic;this.metrics=metrics;this.partitions=partitions;this.readiness=readiness;this.capacity=capacity;
        totalTimer=timer("meter.audit.publish.ack");memoryTimer=timer("meter.audit.publish.memory.wait");brokerWaitTimer=timer("meter.audit.publish.future.wait");
    }
    public boolean ready(){return readiness.getAsBoolean();}
    public void publish(List<AuditOperation> operations){
        if(operations.size()>64)throw new AuditUnavailableException("Audit batch exceeds bound");
        long started=System.nanoTime();
        try (var reservation=reserve(operations)) {
            if(!capacity.test(reservation.encodedBytes()*2))throw new AuditUnavailableException("Audit backlog or broker disk capacity unavailable");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            // At most eight wire records outstanding, even for a maximum-size request body.
            var pending=new ArrayList<Future<RecordMetadata>>(8);
            for(AuditOperation op:operations)for(AuditEnvelope part:codec.split(op)){
                if(System.nanoTime()>=deadline)throw new TimeoutException("Audit operation deadline exceeded");
                // Explicit fixed partition mapping avoids metadata expansion reordering an active request.
                pending.add(producer.send(new ProducerRecord<>(topic,Math.floorMod(op.requestId().hashCode(),partitions),op.requestId(),codec.encode(part))));
                if(pending.size()==8){ack(pending,deadline);pending.clear();}
            }
            ack(pending,deadline);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AuditUnavailableException("Audit publish interrupted",e);}
        catch(Exception e){metrics.counter("meter.audit.publish.failures").increment();throw new AuditUnavailableException("Audit acknowledgement unavailable",e);}
        finally{totalTimer.record(System.nanoTime()-started,TimeUnit.NANOSECONDS);}
    }
    private void ack(List<Future<RecordMetadata>> pending,long deadline)throws Exception{
        if(pending.isEmpty())return;
        long started=System.nanoTime();
        try { for(var future:pending)future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
        finally { brokerWaitTimer.record(System.nanoTime()-started,TimeUnit.NANOSECONDS); }
    }
    private AuditMemoryBudget.Reservation reserve(List<AuditOperation> operations)throws InterruptedException{
        long started=System.nanoTime();
        try { return memory.reserve(operations); }
        finally { memoryTimer.record(System.nanoTime()-started,TimeUnit.NANOSECONDS); }
    }
    public void close(){producer.close(Duration.ofSeconds(15));}
}
