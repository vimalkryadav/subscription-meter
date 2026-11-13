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
    private final RefreshSessionRepository refresh;
    public record Tokens(String access,String refresh,String accountId,String csrf) {}
    public SessionService(SessionRepository sessions,AccountRepository accounts,RefreshSessionRepository refresh) { this.sessions=sessions; this.accounts=accounts; this.refresh=refresh; }
    @Transactional
    public String create(String accountId,String previous) {
        return createLegacy(accountId,previous,null);
    }
    @Transactional
    public String createLegacy(String accountId,String previous,String previousRefresh) {
        accounts.lock(accountId);
        logout(previous,previousRefresh);
        Instant now=Instant.now();
        sessions.prune(accountId,now,now.minusSeconds(3600));
        String raw=PortalTokens.generate();
        sessions.add(PortalTokens.hash(raw),accountId,now,now.plusSeconds(43200));
        return raw;
    }
    public String authenticate(String raw) {
        if(raw==null || raw.length()>100) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Instant now=Instant.now();
        var access=refresh.access(PortalTokens.hash(raw),now);
        if(access.isPresent()) {
            if(!refresh.touch(access.get().family(),now)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            return access.get().accountId();
        }
        return sessions.authenticate(PortalTokens.hash(raw),now,now.minusSeconds(3600))
                .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    public String actor(jakarta.servlet.http.HttpServletRequest request) {
        Object verified=request.getAttribute(PortalAuthFilter.ACCOUNT);
        return verified instanceof String id?id:authenticate(PortalAuthFilter.cookie(request));
    }
    public String csrf(String raw) {
        if(raw==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return refresh.csrf(PortalTokens.hash(raw)).orElseGet(()->PortalTokens.csrf(raw));
    }
    public boolean persistent(String raw){return raw!=null&&refresh.familyForAccess(PortalTokens.hash(raw)).isPresent();}
    @Transactional public void logout(String raw) {
        if(raw!=null) { String hash=PortalTokens.hash(raw);refresh.familyForAccess(hash).ifPresent(refresh::delete);sessions.delete(hash); }
    }
    @Transactional public void logout(String raw,String refreshToken){
        logout(raw);
        if(valid(refreshToken))refresh.familyForRefresh(PortalTokens.hash(refreshToken)).ifPresent(refresh::delete);
    }
    private static boolean valid(String raw){return raw!=null&&raw.matches("[A-Za-z0-9_-]{43}");}
    private Tokens issue(String family,String accountId,String csrf,Instant now){
        String access=PortalTokens.generate(),renewal=PortalTokens.generate();
        refresh.issue(family,PortalTokens.hash(access),PortalTokens.hash(renewal),RefreshReceipt.seal(renewal,access,renewal),now);
        return new Tokens(access,renewal,accountId,csrf);
    }
    @Transactional public Tokens createPersistent(String accountId,String previous,String previousRefresh){
        accounts.lock(accountId);logout(previous,previousRefresh);
        Instant now=Instant.now();String family=PortalTokens.hash(PortalTokens.generate());
        sessions.add(family,accountId,now,Instant.parse("9999-12-31T00:00:00Z"));
        String csrf=PortalTokens.csrf(PortalTokens.generate());refresh.create(family,csrf);
        return issue(family,accountId,csrf,now);
    }
    @Transactional(noRollbackFor=ResponseStatusException.class)
    public Tokens renew(String raw){
        if(!valid(raw))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String hash=PortalTokens.hash(raw);
        var token=refresh.lock(hash).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Instant now=Instant.now();
        if(token.used()!=null){
            if(token.used().isBefore(now.minusSeconds(30))) {
                refresh.delete(token.family());throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }
            String[] pair=RefreshReceipt.open(raw,token.receipt());
            return new Tokens(pair[0],pair[1],token.accountId(),token.csrf());
        }
        // A retry/current cookie immediately after rotation receives the same pair.
        if(token.created().isAfter(now.minusSeconds(30))){
            String[] pair=RefreshReceipt.open(raw,token.receipt());
            return new Tokens(pair[0],pair[1],token.accountId(),token.csrf());
        }
        Tokens next=issue(token.family(),token.accountId(),token.csrf(),now);
        refresh.consume(hash,RefreshReceipt.seal(raw,next.access(),next.refresh()),now);
        refresh.touch(token.family(),now);return next;
    }
}
