package dev.subscriptionmeter.accounting;

import dev.subscriptionmeter.portal.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/admin")
public class AccountingController {
    private final AccountingService accounting;
    private final SessionService sessions;
    public AccountingController(AccountingService accounting,SessionService sessions){this.accounting=accounting;this.sessions=sessions;}
    @GetMapping("/accounting") public Map<String,Object> report(@RequestParam(defaultValue="7") int days,
            @RequestParam(defaultValue="") String memberId,@RequestParam(defaultValue="") String provider) {
        if(days<1 || days>90 || memberId.length()>36 || !Set.of("","anthropic","openai").contains(provider))throw new IllegalArgumentException("Invalid filters");
        Instant to=Instant.now(); return accounting.report(to.minus(days,ChronoUnit.DAYS),to,memberId,provider);
    }
    @GetMapping("/requests/{id}/accounting") public Map<String,Object> detail(@PathVariable String id){return accounting.detail(id);}
    public record ClassificationUpdate(String category){}
    @PatchMapping("/requests/{id}/classification") public Map<String,Object> classify(@PathVariable String id,
            @RequestBody ClassificationUpdate body,HttpServletRequest request) {
        String actor=sessions.actor(request);
        if(body==null||body.category()==null)throw new IllegalArgumentException("Category required");
        accounting.classify(id,body.category(),actor); return accounting.detail(id);
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(){return Map.of("error","Invalid accounting request");}
}
