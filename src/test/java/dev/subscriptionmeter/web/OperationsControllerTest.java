package dev.subscriptionmeter.web;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OperationsControllerTest {
    @TempDir Path folder;
    final Instant now=Instant.parse("2026-11-05T00:00:00Z");
    String report(Instant generated) {
        var rows=new ArrayList<Map<String,Object>>();
        for(String id:List.of("gateway","prometheus","worker_ready","worker_lag","audit_guard","upstream_auth","disk","backup","restore"))
            rows.add(Map.of("id",id,"state","ok","value",1,"consecutiveFailures",0,"unit","secret-unit","label","secret-label","payload","secret-capture"));
        return JsonMapper.builder().build().writeValueAsString(Map.of("schemaVersion",1,"generatedAt",generated.toString(),"checks",rows,"secret","credential"));
    }
    @Test void missingMalformedAndOversizeReportsFailClosed() throws Exception {
        Path file=folder.resolve("report.json");var controller=new OperationsController(file.toString(),Clock.fixed(now,ZoneOffset.UTC));
        assertThat(controller.operations().status()).isEqualTo("unavailable");
        Files.writeString(file,"{}");assertThat(controller.operations().stale()).isTrue();
        Files.writeString(file,"x".repeat(32769));assertThat(controller.operations().checks()).isEmpty();
    }
    @Test void whitelistsMetadataAndMarksStaleAndFutureReports() throws Exception {
        Path file=folder.resolve("report.json");var controller=new OperationsController(file.toString(),Clock.fixed(now,ZoneOffset.UTC));
        Files.writeString(file,report(now));var result=controller.operations();
        assertThat(result.status()).isEqualTo("ok");assertThat(result.checks()).hasSize(9);
        assertThat(JsonMapper.builder().build().writeValueAsString(result)).doesNotContain("secret", "credential");
        Files.writeString(file,report(now.minusSeconds(181)));assertThat(controller.operations().stale()).isTrue();
        Files.writeString(file,report(now.plusSeconds(60)));assertThat(controller.operations().status()).isEqualTo("unavailable");
    }
    @Test void recoveryReportRejectsUnknownActionsAndStaleTimestamps() throws Exception {
        var controller=new OperationsController(folder.resolve("report.json").toString(),Clock.fixed(now,ZoneOffset.UTC));
        assertThat(controller.recovery().stale()).isTrue();
        var report=new HashMap<String,Object>();report.put("schemaVersion",1);report.put("generatedAt",now.toString());
        report.put("status","needs_attention");report.put("action","none");report.put("failedChecks",List.of("gateway"));report.put("secret","not exported");
        Path file=folder.resolve("recovery.json");var json=JsonMapper.builder().build();Files.writeString(file,json.writeValueAsString(report));
        assertThat(controller.recovery().status()).isEqualTo("needs_attention");assertThat(controller.recovery().stale()).isFalse();
        assertThat(json.writeValueAsString(controller.recovery())).doesNotContain("not exported");
        report.put("generatedAt",now.minusSeconds(4501).toString());Files.writeString(file,json.writeValueAsString(report));assertThat(controller.recovery().stale()).isTrue();
        report.put("action","arbitrary-command");Files.writeString(file,json.writeValueAsString(report));assertThat(controller.recovery().status()).isEqualTo("unavailable");
    }
    @Test void endpointRequiresLiveAdminAuthority() throws Exception {
        var accounts=org.mockito.Mockito.mock(dev.subscriptionmeter.portal.AccountRepository.class);
        var sessions=org.mockito.Mockito.mock(dev.subscriptionmeter.portal.SessionService.class);
        org.mockito.Mockito.when(accounts.hasAdmin()).thenReturn(true);
        for(String role:List.of("admin","member")) {
            org.mockito.Mockito.when(sessions.authenticate(role)).thenReturn(role);
            org.mockito.Mockito.when(accounts.byId(role)).thenReturn(Optional.of(new dev.subscriptionmeter.portal.Account(role,role,role,"unused",role)));
        }
        org.mockito.Mockito.when(sessions.authenticate("expired")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        Path secret=folder.resolve("synthetic-secret");Files.writeString(secret,"synthetic-test-secret-"+UUID.randomUUID());
        var filter=new dev.subscriptionmeter.security.AdminAuthFilter(secret.toString(),secret.toString(),accounts,sessions);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new OperationsController("",Clock.fixed(now,ZoneOffset.UTC))).addFilters(filter).build();
        for(var entry:Map.of("admin",200,"member",403,"expired",401).entrySet()) {
            var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/operations").servletPath("/admin/operations").cookie(new jakarta.servlet.http.Cookie("meter_session",entry.getKey()))).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(entry.getValue());
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/operations").servletPath("/admin/operations")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/recovery").servletPath("/admin/recovery")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

}
