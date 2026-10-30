package dev.subscriptionmeter.web;

import dev.subscriptionmeter.audit.AuditStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
public class RequestInspectorController {
    private final AuditStore audit;
    public RequestInspectorController(AuditStore audit) { this.audit=audit; }
    private void check(int days,int limit,int offset) {
        if (days<1 || days>365 || limit<1 || limit>100 || offset<0 || offset>100000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
    }
    @GetMapping("/requests") public List<Map<String,Object>> requests(
            @RequestParam(defaultValue="7") int days,@RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="") String memberId) {
        check(days,limit,offset);
        return audit.list(Instant.now().minus(days,ChronoUnit.DAYS),memberId,limit,offset);
    }
    @GetMapping("/requests/{id}") public Map<String,Object> detail(@PathVariable String id) {
        return audit.detail(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @GetMapping("/requests/{id}/events") public List<Map<String,Object>> events(@PathVariable String id,
            @RequestParam(defaultValue="0") int after,@RequestParam(defaultValue="100") int limit) {
        if (after<0 || limit<1 || limit>500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return audit.events(id,after,limit);
    }
    @GetMapping("/access") public List<Map<String,Object>> access(@RequestParam(defaultValue="7") int days,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        check(days,limit,offset); return audit.accesses(Instant.now().minus(days,ChronoUnit.DAYS),limit,offset);
    }
}
