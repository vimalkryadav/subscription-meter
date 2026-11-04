package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.*;
import dev.subscriptionmeter.messaging.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditPipelineTest {
    private AuditContext context(){return new AuditContext("request",Instant.now(),"POST","/v1/messages",null,Map.of(),"localhost","HTTP/1.1");}
    private AuditCapture strict(AuditStore store){when(store.requiresAcknowledgedForwarding()).thenReturn(true);return new AuditCapture(store,new CredentialRedactor(),new SimpleMeterRegistry(),context(),"member","subscription",new byte[0],1048576,100);}
    @Test void batchesCompleteFramesBeforeReleasingOriginalBytes()throws Exception{
        AuditStore store=mock(AuditStore.class);var capture=strict(store);capture.headers(Map.of(),true);
        var output=new java.io.ByteArrayOutputStream();
        String first="event: delta\r\ndata: {\"text\":\"🌏\"}\r\n\r\n";
        byte[] bytes=(first+"data: {}\n\n").getBytes(StandardCharsets.UTF_8);
        doAnswer(inv->{assertThat(output.size()).isZero();assertThat((List<?>)inv.getArgument(0)).hasSize(2);return null;}).when(store).eventsBatch(anyList());
        capture.forward(bytes,bytes.length,(b,n)->output.write(b,0,n));
        assertThat(output.toByteArray()).isEqualTo(bytes);verify(store,times(1)).eventsBatch(anyList());
    }
    @Test void partialFrameIsNotForwardedAndPublishFailureStopsRelease()throws Exception{
        AuditStore store=mock(AuditStore.class);var capture=strict(store);capture.headers(Map.of(),true);
        var output=new java.io.ByteArrayOutputStream();byte[] first="data: {".getBytes();
        capture.forward(first,first.length,(b,n)->output.write(b,0,n));assertThat(output.size()).isZero();
        doThrow(new IllegalStateException("broker unavailable")).when(store).eventsBatch(anyList());
        byte[] last="}\n\n".getBytes();
        assertThatThrownBy(()->capture.forward(last,last.length,(b,n)->output.write(b,0,n))).isInstanceOf(AuditUnavailableException.class);
        assertThat(output.size()).isZero();
    }
    @Test void nonstreamBodyIsAcknowledgedBeforeAnyForwarding()throws Exception{
        AuditStore store=mock(AuditStore.class);var capture=strict(store);capture.headers(Map.of(),false);
        var output=new java.io.ByteArrayOutputStream();byte[] bytes="{\"text\":\"result\"}".getBytes();
        capture.forward(bytes,bytes.length,(b,n)->output.write(b,0,n));assertThat(output.size()).isZero();
        doAnswer(inv->{assertThat(output.size()).isZero();return null;}).when(store).event(anyString(),any(),anyInt(),anyLong(),anyString(),anyString(),anyBoolean());
        capture.completeForwarding((b,n)->output.write(b,0,n));assertThat(output.toByteArray()).isEqualTo(bytes);
    }
    @Test void codecChunksUtf8WithoutLosingDataAndUsesStableIdentity(){
        var codec=new AuditCodec();var op=new AuditOperation("r:BEGIN","r","BEGIN",Instant.now().toString(),Map.of("body","🌏".repeat(120000)));
        var out=new java.io.ByteArrayOutputStream();int parts=0;
        for(var envelope:codec.split(op)){
            var roundtrip=codec.decode(codec.encode(envelope));assertThat(roundtrip.operationId()).isEqualTo(op.operationId());
            out.writeBytes(Base64.getDecoder().decode(roundtrip.data()));parts++;
        }
        assertThat(parts).isGreaterThan(1);assertThat(codec.fields(out.toByteArray())).isEqualTo(op.fields());
    }
    @Test void producerFailureIsNotReportedAsAcknowledged(){
        var producer=new MockProducer<String,byte[]>(true,null,new StringSerializer(),new ByteArraySerializer());
        producer.sendException=new org.apache.kafka.common.KafkaException("offline");
        var publisher=new KafkaAuditPublisher(producer,"audit",new SimpleMeterRegistry());
        assertThatThrownBy(()->publisher.publish(List.of(new AuditOperation("r:BEGIN","r","BEGIN",Instant.now().toString(),Map.of()))))
                .isInstanceOf(AuditUnavailableException.class);
    }
}
