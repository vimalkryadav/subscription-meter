package dev.subscriptionmeter.web;

import dev.subscriptionmeter.deployment.DeploymentState;
import dev.subscriptionmeter.portal.*;
import dev.subscriptionmeter.security.AdminAuthFilter;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;

class StreamProbeControllerTest {
    @TempDir Path folder;
    static MockHttpServletRequest request() { var r=new MockHttpServletRequest();r.setCookies(new Cookie("meter_session","admin"));return r; }
    @Test void fixedSequenceFlushesAllEventsAndReleasesDrainPermit() throws Exception {
        var state=new DeploymentState();var clock=new AtomicLong();var gaps=new ArrayList<Long>();
        var controller=new StreamProbeController(state,millis->{gaps.add(millis);clock.addAndGet(millis*1_000_000);},clock::get);
        var response=spy(new MockHttpServletResponse());controller.probe(request(),response);
        String[] events=response.getContentAsString().strip().split("\n\n");
        assertThat(events).hasSize(16);assertThat(response.getContentAsByteArray().length).isLessThan(4096);
        for(int i=0;i<16;i++) {
            var event=JsonMapper.builder().build().readTree(events[i].substring(6));
            assertThat(event.path("sequence").asInt()).isEqualTo(i);
            assertThat(event.path("total").asInt()).isEqualTo(16);
            assertThat(event.path("serverElapsedMs").asLong()).isEqualTo(i*200L);
            assertThat(event.path("complete").asBoolean()).isEqualTo(i==15);
        }
        assertThat(gaps).hasSize(15).allMatch(v->v==200L);
        verify(response,times(16)).flushBuffer();assertThat(state.active()).isZero();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-transform");
    }
    @Test void disconnectInterruptionAndDrainingReleaseAllReservations() throws Exception {
        var state=new DeploymentState();var controller=new StreamProbeController(state,millis->{},System::nanoTime);
        var disconnected=mock(HttpServletResponse.class);var output=mock(jakarta.servlet.ServletOutputStream.class);
        when(disconnected.getOutputStream()).thenReturn(output);doThrow(new IOException("fixture disconnected")).when(output).write(any(byte[].class));
        for(int i=0;i<21;i++)assertThatThrownBy(()->controller.probe(request(),disconnected)).isInstanceOf(IOException.class);
        assertThat(state.active()).isZero();
        var interrupted=new StreamProbeController(state,millis->{throw new InterruptedException();},System::nanoTime);
        try {
            for(int i=0;i<21;i++) {
                assertThatThrownBy(()->interrupted.probe(request(),new MockHttpServletResponse())).isInstanceOf(IOException.class);
                assertThat(Thread.interrupted()).isTrue();
            }
        } finally {Thread.interrupted();}
        assertThat(state.active()).isZero();
        state.drain();
        for(int i=0;i<21;i++)assertThatThrownBy(()->controller.probe(request(),new MockHttpServletResponse())).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));
        state.resume();controller.probe(request(),new MockHttpServletResponse());assertThat(state.active()).isZero();
    }
    @Test void twentyConcurrentProbesRejectTwentyFirstBeforeWritingHeaders() throws Exception {
        var state=new DeploymentState();var entered=new CountDownLatch(20);var release=new CountDownLatch(1);
        var controller=new StreamProbeController(state,millis->{entered.countDown();release.await();},System::nanoTime);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=new ArrayList<Future<?>>();
            try {
                for(int i=0;i<20;i++)pending.add(executor.submit(()->{controller.probe(request(),new MockHttpServletResponse());return null;}));
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(state.active()).isEqualTo(20);
                var response=mock(HttpServletResponse.class);
                assertThatThrownBy(()->controller.probe(request(),response)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(429));
                verifyNoInteractions(response);
            } finally {release.countDown();}
            for(var future:pending)future.get(5,TimeUnit.SECONDS);
        }
        assertThat(state.active()).isZero();controller.probe(request(),new MockHttpServletResponse());
    }
    @Test void onlyCurrentAdminSessionWithCsrfCanPost() throws Exception {
        var accounts=mock(AccountRepository.class);var sessions=mock(SessionService.class);when(accounts.hasAdmin()).thenReturn(true);
        for(String role:List.of("admin","member")) {
            when(sessions.authenticate(role)).thenReturn(role);
            when(accounts.byId(role)).thenReturn(Optional.of(new Account(role,role,role,"unused",role)));
        }
        when(sessions.authenticate("expired")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        var secret=folder.resolve("secret");Files.writeString(secret,"synthetic-test-secret-"+UUID.randomUUID());
        var filter=new AdminAuthFilter(secret.toString(),secret.toString(),accounts,sessions);
        var controller=new StreamProbeController(new DeploymentState(),millis->{},System::nanoTime);
        var mvc=standaloneSetup(controller).addFilters(filter).build();String path="/admin/operations/stream-probe";
        assertThat(mvc.perform(post(path).servletPath(path)).andReturn().getResponse().getStatus()).isEqualTo(401);
        for(var entry:Map.of("member",403,"expired",401,"admin",200).entrySet()) {
            var response=mvc.perform(post(path).servletPath(path).cookie(new Cookie("meter_session",entry.getKey())).header("X-Portal-Request","1").header("X-CSRF-Token",PortalTokens.csrf(entry.getKey()))).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(entry.getValue());
        }
        assertThat(mvc.perform(post(path).servletPath(path).cookie(new Cookie("meter_session","admin"))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(path).servletPath(path).cookie(new Cookie("meter_session","admin"))).andReturn().getResponse().getStatus()).isEqualTo(405);
        when(accounts.hasAdmin()).thenReturn(false);
        assertThat(mvc.perform(post(path).servletPath(path).header("Authorization","Bearer "+Files.readString(secret))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
