package dev.subscriptionmeter.messaging;

import java.util.concurrent.*;
import java.util.function.LongSupplier;
import io.micrometer.core.instrument.MeterRegistry;

/** Reclaims only already committed history under pressure; never advances consumer offsets. */
public final class KafkaAuditMaintenance implements AutoCloseable {
    public static final long HIGH_WATER_BYTES=512L*1024*1024;
    @FunctionalInterface public interface Prune { void run() throws Exception; }
    private final LongSupplier bytes;
    private final Prune prune;
    private final MeterRegistry metrics;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("audit-capacity-maintenance").factory());
    public KafkaAuditMaintenance(LongSupplier bytes,Prune prune,MeterRegistry metrics){this.bytes=bytes;this.prune=prune;this.metrics=metrics;}
    public void start(){executor.scheduleWithFixedDelay(this::check,5,5,TimeUnit.SECONDS);}
    public void check(){
        if(bytes.getAsLong()<HIGH_WATER_BYTES)return; // negative means no fresh, valid broker observation
        try{prune.run();metrics.counter("meter.audit.capacity.prune","outcome","success").increment();}
        catch(Exception failure){
            metrics.counter("meter.audit.capacity.prune","outcome","failure").increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Committed audit capacity cleanup failed ({}); admission guard remains enforced",failure.getClass().getSimpleName());
        }
    }
    public void close(){executor.shutdownNow();}
}
