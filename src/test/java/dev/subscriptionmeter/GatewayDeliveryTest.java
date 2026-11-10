package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.*;
import dev.subscriptionmeter.domain.*;
import dev.subscriptionmeter.gateway.*;
import dev.subscriptionmeter.limits.InMemoryRequestLimiter;
import dev.subscriptionmeter.security.MemberService;
import dev.subscriptionmeter.upstream.UpstreamTransport;
import dev.subscriptionmeter.web.GatewayController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayDeliveryTest {
    final Member member=new Member("fixture","Fixture","unused",true,60,4);
    final RequestLedger ledger=mock(RequestLedger.class);
    final AuditStore store=mock(AuditStore.class);
    final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    GatewayDeliveryTest(){when(store.requiresAcknowledgedForwarding()).thenReturn(true);when(store.ready()).thenReturn(true);}
    UpstreamTransport transport(String type,String prefix,boolean readFailure) {
        return (method,path,headers,body)->new UpstreamTransport.Reply(){
            final InputStream input=new InputStream(){
                boolean emitted;
                public int read()throws IOException{throw new IOException("bulk fixture only");}
                public int read(byte[] bytes,int offset,int length)throws IOException{
                    if(!emitted&&!prefix.isEmpty()){emitted=true;byte[] raw=prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);System.arraycopy(raw,0,bytes,offset,raw.length);return raw.length;}
                    if(readFailure)throw new IOException("fixture upstream failed");return -1;
                }
            };
            public int status(){return 200;}
            public Map<String,List<String>> headers(){return Map.of("content-type",List.of(type),"request-id",List.of("upstream-fixture"));}
            public InputStream body(){return input;}
            public void close()throws IOException{input.close();}
        };
    }
    GatewayService service(UpstreamTransport transport){return new GatewayService(new InMemoryRequestLimiter(),transport,ledger,new GatewayMetrics(registry),new AuditCaptureFactory(store,new CredentialRedactor(),registry,"subscription",8388608,10000));}
    org.springframework.mock.web.MockHttpServletResponse request(UpstreamTransport transport)throws Exception{
        var members=mock(MemberService.class);when(members.authenticate(anyString())).thenReturn(member);
        var mvc=MockMvcBuilders.standaloneSetup(new GatewayController(members,service(transport)))
                .addFilters(new GatewayAccessFilter(store,new CredentialRedactor(),registry)).build();
        return mvc.perform(post("/v1/messages").servletPath("/v1/messages").header("Authorization","Bearer fixture").contentType("application/json").content("{}")).andReturn().getResponse();
    }
    @Test void headersThenReadFailureReturns502WithMatchingLedgerAndAccess()throws Exception{
        var response=request(transport("text/event-stream","",true));
        assertThat(response.getStatus()).isEqualTo(502);assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getContentType()).isNotEqualTo("text/event-stream");assertThat(response.getHeader("request-id")).isNull();
        assertThat(response.getHeader("x-meter-request-id")).isNotBlank();assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(ledger).finish(anyString(),argThat(r->r.status()==502&&r.state().equals("upstream_error")));
        verify(store).access(any(),anyString(),eq(502),anyLong(),anyString(),nullable(String.class));
        assertThat(registry.get("meter.gateway.phase").tag("phase","first_forward").timer().count()).isZero();
    }
    @Test void partialUnacknowledgedFrameIsNotTreatedAsClientDelivery()throws Exception{
        var response=request(transport("text/event-stream","data: {\"type\":\"ping\"}",true));
        assertThat(response.getStatus()).isEqualTo(502);assertThat(response.getContentAsByteArray()).isEmpty();
        verify(store).event(anyString(),any(),anyInt(),anyLong(),anyString(),anyString(),eq(true));
        verify(ledger).finish(anyString(),argThat(r->r.status()==502));
    }
    @Test void forwardedFrameKeeps200AndExactPrefixWhenLaterReadFails()throws Exception{
        String prefix="data: {\"type\":\"ping\"}\n\n";
        var response=request(transport("text/event-stream",prefix,true));
        assertThat(response.getStatus()).isEqualTo(200);assertThat(response.getContentAsString()).isEqualTo(prefix);
        verify(ledger).finish(anyString(),argThat(r->r.status()==200&&r.state().equals("partial")));
        assertThat(registry.get("meter.gateway.phase").tag("phase","first_forward").timer().count()).isEqualTo(1);
    }
    @Test void failedFirstFrameAcknowledgementReturns503AndMatchingLedger()throws Exception{
        doThrow(new AuditUnavailableException("fixture audit failed")).when(store).eventsBatch(anyList());
        var response=request(transport("text/event-stream","data: {\"type\":\"ping\"}\n\n",false));
        assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getHeader("Retry-After")).isEqualTo("10");assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getContentType()).isNotEqualTo("text/event-stream");
        verify(ledger).finish(anyString(),argThat(r->r.status()==503&&r.state().equals("gateway_error")));
        verify(store).finish(anyString(),argThat(fields->fields.get("capture_state").equals("capture_error")));
    }
    @Test void incompleteFrameAtEofIs503BeforeCommit()throws Exception{
        var response=request(transport("text/event-stream","data: {\"type\":\"ping\"}",false));
        assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getHeader("Retry-After")).isEqualTo("10");assertThat(response.getContentAsByteArray()).isEmpty();
        verify(ledger).finish(anyString(),argThat(r->r.status()==503));
    }
    @Test void terminalCaptureFailureBeforeCommitUpdatesLedgerToActual503()throws Exception{
        doThrow(new AuditUnavailableException("fixture finish failed")).when(store).finish(anyString(),anyMap());
        var response=request(transport("text/event-stream","",true));
        assertThat(response.getStatus()).isEqualTo(503);
        verify(ledger).finish(anyString(),argThat(r->r.status()==503&&r.state().equals("gateway_error")));
    }
    @Test void bufferedNonstreamDisconnectIsRecordedConsistentlyBeforeAndAfterCommit(){
        for(boolean partialCommit:List.of(false,true)){
            reset(ledger);
            service(transport("application/json","{\"usage\":{\"input_tokens\":1}}",false)).forward(member,
                    new AuditContext(UUID.randomUUID().toString(),Instant.now(),"POST","/v1/messages",null,Map.of(),"127.0.0.1","HTTP/1.1"),
                    "fixture",Map.of(),"{}".getBytes(),new ResponseSink(){
                        boolean attempted;
                        public void start(int status,Map<String,String> headers){}
                        public boolean committed(){return partialCommit&&attempted;}
                        public void write(byte[] bytes,int length)throws IOException{attempted=true;throw new IOException("fixture disconnect");}
                    });
            verify(ledger).finish(anyString(),argThat(r->r.status()==(partialCommit?200:499)&&r.state().equals("disconnected")));
        }
    }
    @Test void upstreamWaitHasItsOwnBoundedHistogram()throws Exception{
        var immediate=transport("application/json","{}",false);
        request((method,path,headers,body)->{Thread.sleep(25);return immediate.send(method,path,headers,body);});
        var timer=registry.get("meter.gateway.phase").tag("phase","upstream_headers").timer();
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(20);
        assertThat(timer.takeSnapshot().histogramCounts()).hasSize(12);
        assertThat(registry.get("meter.gateway.phase").tag("phase","access_audit").timer().count()).isEqualTo(1);
        assertThat(registry.get("meter.gateway.phase").tag("phase","total_gateway").timer().count()).isEqualTo(1);
    }
}
