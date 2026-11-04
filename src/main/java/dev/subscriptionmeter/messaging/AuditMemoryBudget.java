package dev.subscriptionmeter.messaging;

import dev.subscriptionmeter.audit.AuditUnavailableException;
import java.util.*;
import java.util.concurrent.*;

/** Reserve encoding and wire-buffer headroom before serializing any operation. */
final class AuditMemoryBudget {
    private static final int UNIT=65536,UNITS=2048; // 128MiB per publisher, separate from Kafka's buffer.
    private final Semaphore memory=new Semaphore(UNITS,true),slots=new Semaphore(20,true),large=new Semaphore(1,true);
    Reservation reserve(List<AuditOperation> operations)throws InterruptedException{
        long size=0;for(var op:operations)size=Math.addExact(size,estimate(op.fields()));
        long needed=(Math.multiplyExact(size,3)+1048576+UNIT-1)/UNIT;
        if(needed>UNITS)throw new AuditUnavailableException("Audit batch exceeds serialization memory budget");
        boolean big=size>1048576,slot=false,largeSlot=false,bytes=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        try{
            slot=slots.tryAcquire(remaining(deadline),TimeUnit.NANOSECONDS);
            if(!slot)throw new AuditUnavailableException("Audit publisher slots unavailable");
            if(big){largeSlot=large.tryAcquire(remaining(deadline),TimeUnit.NANOSECONDS);if(!largeSlot)throw new AuditUnavailableException("Large audit publisher capacity unavailable");}
            bytes=memory.tryAcquire((int)needed,remaining(deadline),TimeUnit.NANOSECONDS);
            if(!bytes)throw new AuditUnavailableException("Audit publisher memory unavailable");
            return new Reservation((int)needed,big,size);
        }catch(RuntimeException|InterruptedException e){if(bytes)memory.release((int)needed);if(largeSlot)large.release();if(slot)slots.release();throw e;}
    }
    private long remaining(long deadline){return Math.max(0,deadline-System.nanoTime());}
    private static long estimate(Object value){
        if(value==null||value instanceof Boolean)return 5;
        if(value instanceof Number)return 64;
        if(value instanceof String s){
            long n=2;
            for(int i=0;i<s.length();i++){
                char c=s.charAt(i);
                if(c<32)n+=6;
                else if(c=='"'||c=='\\')n+=2;
                else if(c<128)n++;
                else if(c<2048)n+=2;
                else if(Character.isHighSurrogate(c)&&i+1<s.length()&&Character.isLowSurrogate(s.charAt(i+1))){n+=4;i++;}
                else n+=6; // conservatively includes escaped/unpaired UTF-16
            }
            return n;
        }
        if(value instanceof Map<?,?> map){long n=2;for(var entry:map.entrySet())n=Math.addExact(n,estimate(entry.getKey())+estimate(entry.getValue())+2);return n;}
        if(value instanceof Collection<?> list){long n=2;for(var item:list)n=Math.addExact(n,estimate(item)+1);return n;}
        throw new AuditUnavailableException("Unsupported audit envelope value");
    }
    final class Reservation implements AutoCloseable{
        private final int units;private final boolean big;private final long size;private boolean closed;
        Reservation(int units,boolean big,long size){this.units=units;this.big=big;this.size=size;}
        long encodedBytes(){return size;}
        public void close(){if(!closed){closed=true;memory.release(units);if(big)large.release();slots.release();}}
    }
}
