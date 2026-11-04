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
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    @Autowired public StreamProbeController(DeploymentState deployment) { this(deployment,Thread::sleep,System::nanoTime); }
    StreamProbeController(DeploymentState deployment,Sleeper sleeper,LongSupplier nanoTime) {
        this.deployment=deployment;this.sleeper=sleeper;this.nanoTime=nanoTime;
    }
    @PostMapping("/admin/operations/stream-probe")
    public void probe(HttpServletRequest request,HttpServletResponse response) throws IOException {
        // AdminAuthFilter validates current role/session/CSRF; do not allow bootstrap-token fallback here.
        if(PortalAuthFilter.cookie(request)==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(!capacity.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        try(var permit=deployment.enter()) {
            response.setStatus(200);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control","no-store, no-transform");
            response.setHeader("X-Accel-Buffering","no");
            var output=response.getOutputStream();
            long start=nanoTime.getAsLong();
            for(int sequence=0;sequence<16;sequence++) {
                if(sequence>0)sleeper.sleep(200);
                long elapsed=Math.max(0,(nanoTime.getAsLong()-start)/1_000_000);
                String event="data: {\"sequence\":"+sequence+",\"total\":16,\"serverElapsedMs\":"+elapsed+",\"complete\":"+(sequence==15)+"}\n\n";
                output.write(event.getBytes(StandardCharsets.UTF_8));
                response.flushBuffer();
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Stream diagnostic interrupted");
        } finally { capacity.release(); }
    }
}
