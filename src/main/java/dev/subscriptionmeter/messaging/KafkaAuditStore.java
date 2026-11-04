package dev.subscriptionmeter.messaging;

import dev.subscriptionmeter.audit.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

@Repository @Primary
@ConditionalOnProperty(name="meter.audit.transport",havingValue="kafka")
public class KafkaAuditStore implements AuditStore {
    private final AuditPublisher publisher;
    private final JdbcAuditStore queries;
    public KafkaAuditStore(AuditPublisher publisher,JdbcAuditStore queries){this.publisher=publisher;this.queries=queries;}
    public boolean requiresAcknowledgedForwarding(){return true;}
    public boolean ready(){return publisher instanceof KafkaAuditPublisher kafka && kafka.ready();}
    public void headers(String id,String headers,long elapsed,boolean streaming){
        publisher.publish(List.of(operation(id,"HEADERS","",Map.of("response_headers",headers,"upstream_headers_ms",elapsed,"streaming",streaming))));
    }
    private AuditOperation operation(String id,String kind,String suffix,Map<String,Object> fields){
        return new AuditOperation(id+":"+kind+suffix,id,kind,Instant.now().toString(),fields);
    }
    private Map<String,Object> context(AuditContext c){
        var m=new HashMap<String,Object>();m.put("started_at",c.startedAt().toString());m.put("method",c.method());
        m.put("path",c.path());m.put("peer_address",c.peerAddress());m.put("protocol",c.protocol());return m;
    }
    public void begin(AuditContext c,String member,String mode,String headers,String query,String body){
        var m=context(c);m.put("member_id",member);m.put("mode",mode);m.put("headers",headers);m.put("query",query);m.put("body",body);
        publisher.publish(List.of(operation(c.id(),"BEGIN","",m)));
    }
    public void event(String id,Instant time,int seq,long elapsed,String type,String data,boolean truncated){
        eventsBatch(List.of(new AuditEvent(id,time,seq,elapsed,type,data,truncated)));
    }
    public void eventsBatch(List<AuditEvent> events){
        var ops=new ArrayList<AuditOperation>();
        for(var e:events)ops.add(operation(e.id(),"EVENT",":"+e.sequence(),Map.of("started_at",e.requestTime().toString(),
                "sequence",e.sequence(),"elapsed_ms",e.elapsedMs(),"type",e.type(),"data",e.data(),"truncated",e.truncated())));
        publisher.publish(ops);
    }
    public void finish(String id,Map<String,Object> fields){publisher.publish(List.of(operation(id,"FINISH","",fields)));}
    public void access(AuditContext c,String member,int status,long elapsed,String headers,String query){
        var m=context(c);m.put("member_id",member);m.put("status",status);m.put("elapsed_ms",elapsed);m.put("headers",headers);m.put("query",query);
        publisher.publish(List.of(operation(c.id(),"ACCESS","",m)));
    }
    public List<Map<String,Object>> list(Instant s,String m,int l,int o){return queries.list(s,m,l,o);}
    public Optional<Map<String,Object>> detail(String id){return queries.detail(id);}
    public List<Map<String,Object>> events(String id,int a,int l){return queries.events(id,a,l);}
    public List<Map<String,Object>> accesses(Instant s,int l,int o){return queries.accesses(s,l,o);}
}
