package dev.subscriptionmeter.web;

import dev.subscriptionmeter.deployment.DeploymentState;
import dev.subscriptionmeter.portal.PortalAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Fixed transport diagnostics only: no provider calls, request ledger, or usage records. */
@RestController
public class StreamProbeController {
    private final DeploymentState deployment;
    private final Semaphore capacity=new Semaphore(20);
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private final String release;
    private record Profile(int events,long intervalMs) {
        static Profile from(String value) {
            if(value==null || value.equals("quick"))return new Profile(16,200);
            if(value.equals("deployment"))return new Profile(71,500);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    @Autowired public StreamProbeController(DeploymentState deployment,@Value("${meter.release:development}") String release) {
        this(deployment,Thread::sleep,System::nanoTime,release);
    }
    StreamProbeController(DeploymentState deployment,Sleeper sleeper,LongSupplier nanoTime) {
        this(deployment,sleeper,nanoTime,"development");
    }
    StreamProbeController(DeploymentState deployment,Sleeper sleeper,LongSupplier nanoTime,String release) {
        this.deployment=deployment;this.sleeper=sleeper;this.nanoTime=nanoTime;this.release=release;
    }
    @PostMapping("/admin/operations/stream-probe")
    public void probe(HttpServletRequest request,HttpServletResponse response) throws IOException {
        // AdminAuthFilter validates current role/session/CSRF; do not allow bootstrap-token fallback here.
        if(PortalAuthFilter.cookie(request)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var profile=Profile.from(request.getParameter("profile"));
        if(!capacity.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        try(var permit=deployment.enter()) {
            response.setStatus(200);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control","no-store, no-transform");
            response.setHeader("X-Accel-Buffering","no");
            response.setHeader("X-Control-Usage-Release",release);
            var output=response.getOutputStream();
            long start=nanoTime.getAsLong();
            for(int sequence=0;sequence<profile.events();sequence++) {
                if(sequence>0)sleeper.sleep(profile.intervalMs());
                long elapsed=Math.max(0,(nanoTime.getAsLong()-start)/1_000_000);
                String event="data: {\"sequence\":"+sequence+",\"total\":"+profile.events()+",\"serverElapsedMs\":"+elapsed+",\"complete\":"+(sequence==profile.events()-1)+"}\n\n";
                output.write(event.getBytes(StandardCharsets.UTF_8));
                response.flushBuffer();
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Stream diagnostic interrupted");
        } finally { capacity.release(); }
    }
}
