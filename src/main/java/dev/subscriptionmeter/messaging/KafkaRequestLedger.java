package dev.subscriptionmeter.messaging;

import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.persistence.JdbcRequestLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** Admission remains database-backed; terminal usage survives a projection-database outage. */
@Repository @Primary
@ConditionalOnProperty(name="meter.audit.transport",havingValue="kafka")
public final class KafkaRequestLedger implements RequestLedger {
    private final JdbcRequestLedger admission;
    private final AuditPublisher publisher;
    public KafkaRequestLedger(JdbcRequestLedger admission,AuditPublisher publisher){this.admission=admission;this.publisher=publisher;}
    public void begin(String id,String member,String path,String model){admission.begin(id,member,path,model);}
    public void begin(String id,String member,String path,String model,Instant time){admission.begin(id,member,path,model,time);}
    public void markInterrupted(){admission.markInterrupted();}
    public void identify(String id,String provider,String account,String client){admission.identify(id,provider,account,client);}
    public void finish(String id,RequestResult result){
        Usage u=result.usage();var m=new HashMap<String,Object>();
        m.put("state",result.state());m.put("status",result.status());m.put("duration_ms",result.durationMs());m.put("upstream_id",result.upstreamId());
        m.put("input",u.input());m.put("output",u.output());m.put("cache_creation",u.cacheCreation());m.put("cache_read",u.cacheRead());
        m.put("seen",u.seen());m.put("components",u.components());m.put("complete",u.complete());
        publisher.publish(List.of(new AuditOperation(id+":RESULT",id,"RESULT",Instant.now().toString(),m)));
    }
}
