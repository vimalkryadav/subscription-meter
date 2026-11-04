package dev.subscriptionmeter.deployment;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Private deployment API; Caddy's public route allowlist never forwards /internal. */
@RestController
@RequestMapping("/internal/deployment")
public class DeploymentController {
    private final DeploymentState state;
    private final InstanceLease lease;
    private final byte[] token;
    private final dev.subscriptionmeter.audit.AuditStore audit;
    public DeploymentController(DeploymentState state,InstanceLease lease,dev.subscriptionmeter.audit.AuditStore audit,@Value("${meter.deploy-token-file:}") String file) throws IOException {
        this.state=state; this.lease=lease; this.audit=audit;
        String value=file.isBlank()?"":Files.readString(Path.of(file)).strip();
        if(!value.isBlank() && value.length()<32) throw new IllegalArgumentException("Deployment token must be at least 32 characters");
        token=value.getBytes(StandardCharsets.UTF_8);
    }
    private void authenticate(HttpServletRequest request) {
        String supplied=request.getHeader("Authorization");
        if(token.length==0 || supplied==null || !supplied.startsWith("Bearer ") ||
                !MessageDigest.isEqual(token,supplied.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    @GetMapping public ResponseEntity<Map<String,Object>> status(HttpServletRequest request) {
        authenticate(request);
        boolean healthy=lease.healthy();
        boolean auditReady=audit.ready();
        return ResponseEntity.status(healthy?200:503).body(Map.of("instance",lease.id(),"release",lease.release(),
                "ready",healthy&&auditReady&&!state.draining()&&state.unsettled()==0,"auditReady",auditReady,"draining",state.draining(),"active",state.active(),"unsettled",state.unsettled()));
    }
    @PostMapping("/drain") public Map<String,Object> drain(HttpServletRequest request) {
        authenticate(request); state.drain(); return Map.of("draining",true,"active",state.active());
    }
    @PostMapping("/resume") public Map<String,Object> resume(HttpServletRequest request) {
        authenticate(request); lease.requireHealthy(); state.resume(); return Map.of("draining",false);
    }
}
