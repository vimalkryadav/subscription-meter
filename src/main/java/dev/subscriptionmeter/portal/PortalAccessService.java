package dev.subscriptionmeter.portal;

import dev.subscriptionmeter.security.MemberService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PortalAccessService {
    private final AccountRepository accounts;
    private final KeyRequestRepository requests;
    private final MemberService members;
    public PortalAccessService(AccountRepository accounts,KeyRequestRepository requests,MemberService members) {
        this.accounts=accounts; this.requests=requests; this.members=members;
    }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Access state changed. Refresh and try again."); }
    private KeyRequest required(String accountId) { return requests.byAccount(accountId).orElseThrow(this::conflict); }
    public String state(KeyRequest r) {
        return r.state().equals("active") && !members.find(r.memberId()).active()?"revoked":r.state();
    }
    public Map<String,Object> status(String accountId) {
        Map<String,Object> result=new LinkedHashMap<>();
        var request=requests.byAccount(accountId);
        result.put("state",request.map(this::state).orElse("none"));
        request.ifPresent(r->{
            result.put("request",r);
            if(r.memberId()!=null) result.put("member",members.find(r.memberId()));
        });
        result.put("usage",requests.usage(accountId));
        return result;
    }
    @Transactional
    public void request(String accountId,String reason) {
        accounts.lock(accountId);
        requests.byAccount(accountId).ifPresent(r->{
            if(!Set.of("rejected","revoked").contains(state(r))) throw conflict();
        });
        requests.submit(accountId,reason.strip());
    }
    @Transactional
    public void decide(String id,boolean approve,int rpm,int concurrency) {
        var initial=requests.byRequestId(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        accounts.lock(initial.accountId());
        var current=required(initial.accountId());
        if(!current.state().equals("pending")) throw conflict();
        requests.decide(id,approve?"approved":"rejected",rpm,concurrency);
    }
    @Transactional
    public MemberService.IssuedKey issue(String accountId,boolean rotation) {
        var account=accounts.lock(accountId);
        var request=required(accountId);
        if(!state(request).equals(rotation?"active":"approved")) throw conflict();
        var key=request.memberId()==null?members.create(account.displayName()+" (@"+account.username()+")",request.rpm(),request.concurrency()):
                members.rotate(request.memberId(),request.rpm(),request.concurrency(),rotation);
        requests.issued(request.id(),key.member().id());
        return key;
    }
    @Transactional
    public void revoke(String id) {
        var initial=requests.byRequestId(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        accounts.lock(initial.accountId());
        var current=required(initial.accountId());
        if(current.memberId()!=null) members.revoke(current.memberId());
        requests.revoke(id);
    }
    public List<Map<String,Object>> list() {
        return requests.list().stream().map(r->Map.<String,Object>of("request",r,"state",state(r))).toList();
    }
}
