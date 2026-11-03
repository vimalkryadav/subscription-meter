package dev.subscriptionmeter.portal;

import java.util.*;

public interface KeyRequestRepository {
    Optional<KeyRequest> byAccount(String accountId);
    Optional<KeyRequest> byRequestId(String id);
    void submit(String accountId,String reason);
    void decide(String id,String state,int rpm,int concurrency);
    void issued(String id,String memberId);
    void revoke(String id);
    List<KeyRequest> list();
    List<Map<String,Object>> usage(String accountId);
}
