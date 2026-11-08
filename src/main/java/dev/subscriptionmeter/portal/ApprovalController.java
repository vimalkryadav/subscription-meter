package dev.subscriptionmeter.portal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import dev.subscriptionmeter.administration.AdminActionAudit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/key-requests")
public class ApprovalController {
    private final PortalAccessService access;
    private final AdminActionAudit audit;
    public ApprovalController(PortalAccessService access, AdminActionAudit audit) { this.access=access; this.audit=audit; }
    public record Decision(boolean approve,@Min(1) @Max(600) int rpm,@Min(1) @Max(20) int concurrency) {}
    @GetMapping public List<Map<String,Object>> list() { return access.list(); }
    @Transactional
    @PostMapping("/{id}/decision") public Map<String,Boolean> decide(@PathVariable String id,@Valid @RequestBody Decision body,HttpServletRequest request) {
        access.decide(id,body.approve(),body.rpm(),body.concurrency());
        audit.record(request,body.approve()?"key_request.approve":"key_request.reject",id,Map.of("rpm",body.rpm(),"concurrency",body.concurrency()));
        return Map.of("saved",true);
    }
    @Transactional
    @PostMapping("/{id}/revoke") public Map<String,Boolean> revoke(@PathVariable String id,HttpServletRequest request) {
        access.revoke(id);audit.record(request,"key_request.revoke",id,Map.of());return Map.of("revoked",true);
    }
}
