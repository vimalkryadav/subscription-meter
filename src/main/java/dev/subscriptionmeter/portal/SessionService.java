package dev.subscriptionmeter.portal;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionService {
    private final SessionRepository sessions;
    private final AccountRepository accounts;
    public SessionService(SessionRepository sessions,AccountRepository accounts) { this.sessions=sessions; this.accounts=accounts; }
    @Transactional
    public String create(String accountId,String previous) {
        accounts.lock(accountId);
        if(previous!=null) logout(previous);
        Instant now=Instant.now();
        sessions.prune(accountId,now,now.minusSeconds(3600));
        String raw=PortalTokens.generate();
        sessions.add(PortalTokens.hash(raw),accountId,now,now.plusSeconds(43200));
        return raw;
    }
    public String authenticate(String raw) {
        if(raw==null || raw.length()>100) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Instant now=Instant.now();
        return sessions.authenticate(PortalTokens.hash(raw),now,now.minusSeconds(3600))
                .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    public void logout(String raw) { if(raw!=null) sessions.delete(PortalTokens.hash(raw)); }
}
