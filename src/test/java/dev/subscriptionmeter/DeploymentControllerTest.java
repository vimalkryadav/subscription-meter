package dev.subscriptionmeter;

import dev.subscriptionmeter.audit.AuditStore;
import dev.subscriptionmeter.deployment.*;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeploymentControllerTest {
    private CoordinationTest.Fixture fixture() { return new CoordinationTest().fixture(); }
    @Test void privateControlRequiresItsOwnTokenAndReportsDrainAndAuditReadiness() throws Exception {
        var f=fixture(); var state=new DeploymentState(); var owner=new InstanceLease(f.jdbc(),"release");
        var audit=mock(AuditStore.class); when(audit.ready()).thenReturn(true);
        var secret=Files.createTempFile("control-usage-deploy-test-",".token");
        String value="deploy-"+UUID.randomUUID(); Files.writeString(secret,value);
        try {
            var mvc=MockMvcBuilders.standaloneSetup(new DeploymentController(state,owner,audit,secret.toString())).build();
            mvc.perform(get("/internal/deployment")).andExpect(status().isNotFound());
            mvc.perform(post("/internal/deployment/drain").header("Authorization","Bearer wrong-admin-token")).andExpect(status().isNotFound());
            mvc.perform(get("/internal/deployment").header("Authorization","Bearer "+value)).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
            var active=state.enter();
            mvc.perform(post("/internal/deployment/drain").header("Authorization","Bearer "+value)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(1));
            mvc.perform(get("/internal/deployment").header("Authorization","Bearer "+value)).andExpect(jsonPath("$.ready").value(false));
            active.close(); state.resume(); when(audit.ready()).thenReturn(false);
            mvc.perform(get("/internal/deployment").header("Authorization","Bearer "+value)).andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.auditReady").value(false));
            when(audit.ready()).thenReturn(true); state.persistenceFailed();
            mvc.perform(get("/internal/deployment").header("Authorization","Bearer "+value)).andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.unsettled").value(1));
        } finally { Files.deleteIfExists(secret); }
    }
    @Test void missingDeploymentSecretDisablesEndpoint() throws Exception {
        var f=fixture(); var state=new DeploymentState(); var owner=new InstanceLease(f.jdbc(),"release");
        var mvc=MockMvcBuilders.standaloneSetup(new DeploymentController(state,owner,mock(AuditStore.class),"")).build();
        mvc.perform(get("/internal/deployment").header("Authorization","Bearer ")).andExpect(status().isNotFound());
    }
}
