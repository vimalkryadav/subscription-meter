package dev.subscriptionmeter.portal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/key-requests")
public class ApprovalController {
    private final PortalAccessService access;
    public ApprovalController(PortalAccessService access) { this.access=access; }
    public record Decision(boolean approve,@Min(1) @Max(600) int rpm,@Min(1) @Max(20) int concurrency) {}
    @GetMapping public List<Map<String,Object>> list() { return access.list(); }
    @PostMapping("/{id}/decision") public Map<String,Boolean> decide(@PathVariable String id,@Valid @RequestBody Decision body) {
        access.decide(id,body.approve(),body.rpm(),body.concurrency());return Map.of("saved",true);
    }
    @PostMapping("/{id}/revoke") public Map<String,Boolean> revoke(@PathVariable String id) {
        access.revoke(id);return Map.of("revoked",true);
    }
}
