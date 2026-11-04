package dev.subscriptionmeter.web;

import dev.subscriptionmeter.administration.AdminOperations;
import dev.subscriptionmeter.portal.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
public class AdministrationController {
    private final AdminOperations operations;
    private final SessionService sessions;
    public AdministrationController(AdminOperations operations,SessionService sessions){this.operations=operations;this.sessions=sessions;}
    @GetMapping("/accounts") public List<Map<String,Object>> accounts(@RequestParam(defaultValue="") String search){
        if(search.length()>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);return operations.accounts(search);
    }
    @GetMapping("/accounts/{id}/sessions") public List<Map<String,Object>> sessions(@PathVariable String id){return operations.sessions(id);}
    @DeleteMapping("/accounts/{id}/sessions") public Map<String,Integer> revokeAll(@PathVariable String id,HttpServletRequest request){
        return Map.of("revoked",operations.revokeSessions(id,null,actor(request)));
    }
    @DeleteMapping("/accounts/{id}/sessions/{ref}") public Map<String,Integer> revoke(@PathVariable String id,@PathVariable String ref,HttpServletRequest request){
        if(!ref.matches("[a-f0-9]{64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return Map.of("revoked",operations.revokeSessions(id,ref,actor(request)));
    }
    public record Limits(@Min(1) @Max(600) int rpm,@Min(1) @Max(20) int concurrency,
                         @Min(1) @Max(600) int expectedRpm,@Min(1) @Max(20) int expectedConcurrency){}
    @PatchMapping("/members/{id}/limits") public Map<String,String> limits(@PathVariable String id,@Valid @RequestBody Limits body,HttpServletRequest request){
        operations.updateLimits(id,body.rpm(),body.concurrency(),body.expectedRpm(),body.expectedConcurrency(),actor(request));return Map.of("state","updated");
    }
    @GetMapping("/action-log") public List<Map<String,Object>> actions(@RequestParam(defaultValue="") String action,@RequestParam(defaultValue="50") int limit){
        if(action.length()>100||limit<1||limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);return operations.actions(action,limit);
    }
    private String actor(HttpServletRequest request){return sessions.authenticate(PortalAuthFilter.cookie(request));}
}
