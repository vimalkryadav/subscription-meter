package dev.subscriptionmeter.administration;

import java.util.List;
import java.util.Map;

/** Admin operations deliberately exclude role changes and credential retrieval. */
public interface AdminOperations {
    List<Map<String,Object>> accounts(String search);
    List<Map<String,Object>> sessions(String accountId);
    int revokeSessions(String accountId,String sessionRef,String actor);
    void updateLimits(String memberId,int rpm,int concurrency,int expectedRpm,int expectedConcurrency,String actor);
    List<Map<String,Object>> actions(String action,int limit);
}
