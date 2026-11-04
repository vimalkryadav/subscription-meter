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
    public KafkaAuditPublisher(Producer<String,byte[]> producer,String topic,MeterRegistry metrics){
        this(producer,topic,metrics,1,()->true,bytes->true);
    }
    public KafkaAuditPublisher(Producer<String,byte[]> producer,String topic,MeterRegistry metrics,int partitions,java.util.function.BooleanSupplier readiness){
        this(producer,topic,metrics,partitions,readiness,bytes->true);
    }
    public KafkaAuditPublisher(Producer<String,byte[]> producer,String topic,MeterRegistry metrics,int partitions,java.util.function.BooleanSupplier readiness,java.util.function.LongPredicate capacity){
        if(partitions<1)throw new IllegalArgumentException("Invalid audit partition count");
        this.producer=producer;this.topic=topic;this.metrics=metrics;this.partitions=partitions;this.readiness=readiness;this.capacity=capacity;
    }
    public boolean ready(){return readiness.getAsBoolean();}
    public void publish(List<AuditOperation> operations){
        if(operations.size()>64)throw new AuditUnavailableException("Audit batch exceeds bound");
        long started=System.nanoTime();
        try (var reservation=memory.reserve(operations)) {
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
        finally{metrics.timer("meter.audit.publish.ack").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);}
    }
    private void ack(List<Future<RecordMetadata>> pending,long deadline)throws Exception{
        for(var future:pending)future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
    }
    public void close(){producer.close(Duration.ofSeconds(15));}
}
