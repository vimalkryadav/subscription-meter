package dev.subscriptionmeter.accounting;

import dev.subscriptionmeter.portal.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/subscription-invoices")
public class SubscriptionAccountingController {
    private final SubscriptionAccounting service;
    private final SessionService sessions;
    public SubscriptionAccountingController(SubscriptionAccounting service,SessionService sessions){this.service=service;this.sessions=sessions;}
    @GetMapping public List<Map<String,Object>> list(){return service.list();}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,String> create(@RequestBody SubscriptionAccounting.Invoice invoice,HttpServletRequest request){
        String actor=sessions.authenticate(PortalAuthFilter.cookie(request));
        return Map.of("id",service.add(invoice,actor));
    }
    @GetMapping("/{id}/allocation") public Map<String,Object> allocation(@PathVariable String id){return service.allocation(id);}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(){return Map.of("error","Invalid subscription expense");}
}
