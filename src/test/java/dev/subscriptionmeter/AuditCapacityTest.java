package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.*;
import dev.subscriptionmeter.messaging.*;
import dev.subscriptionmeter.deployment.DeploymentState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditCapacityTest {
    @Test void onlyFreshPressureCanPruneAndFailureDoesNotDisableGuard()throws Exception{
        var metric=new SimpleMeterRegistry();var prune=mock(KafkaAuditMaintenance.Prune.class);
        try(var normal=new KafkaAuditMaintenance(()->511L*1024*1024,prune,metric);
            var unknown=new KafkaAuditMaintenance(()->-1L,prune,metric);
            var pressure=new KafkaAuditMaintenance(()->512L*1024*1024,prune,metric)){
            normal.check();unknown.check();verifyNoInteractions(prune);
            pressure.check();verify(prune).run();
            doThrow(new IllegalStateException("unverified watermark")).when(prune).run();
            pressure.check();assertThat(metric.counter("meter.audit.capacity.prune","outcome","failure").count()).isEqualTo(1);
        }
    }
    @Test void unavailableAuditReturns503BeforeCallingGatewayOrCreatingUnsettledFailures()throws Exception{
        var store=mock(AuditStore.class);when(store.requiresAcknowledgedForwarding()).thenReturn(true);when(store.ready()).thenReturn(false);
        var state=new DeploymentState();var filter=new GatewayAccessFilter(store,new CredentialRedactor(),new SimpleMeterRegistry(),state);
        var request=new MockHttpServletRequest("POST","/v1/messages");request.setServletPath("/v1/messages");
        var response=new MockHttpServletResponse();var called=new boolean[]{false};
        filter.doFilter(request,response,(req,res)->called[0]=true);
        assertThat(called[0]).isFalse();assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("10");assertThat(state.unsettled()).isZero();assertThat(state.active()).isZero();
        verify(store,never()).access(any(),any(),anyInt(),anyLong(),any(),any());
    }
    @RestController static class FailingController {
        @GetMapping("/failure") void fail(){throw new AuditUnavailableException("private internal reason",new IllegalStateException("private cause"));}
    }
    @Test void auditFailureRacingAdmissionStillHas503Semantics()throws Exception{
        MockMvcBuilders.standaloneSetup(new FailingController()).build().perform(get("/failure")).andExpect(status().isServiceUnavailable());
        assertThat(new AuditUnavailableException("private reason").getReason()).isEqualTo("Audit logging temporarily unavailable");
    }
}
