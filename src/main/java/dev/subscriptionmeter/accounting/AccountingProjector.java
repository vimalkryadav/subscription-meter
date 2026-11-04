package dev.subscriptionmeter.accounting;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.LoggerFactory;

@Component
public class AccountingProjector {
    private final AccountingService service;
    private final boolean enabled;
    public AccountingProjector(AccountingService service,@Value("${meter.accounting.enabled:true}") boolean enabled){this.service=service;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${meter.accounting.interval-ms:5000}",initialDelay=10000)
    public void run() {
        if(!enabled)return;
        try{service.projectPending();}
        catch(RuntimeException e){LoggerFactory.getLogger(getClass()).warn("Accounting projection delayed; retrying next interval ({})",e.getClass().getSimpleName());}
    }
}
