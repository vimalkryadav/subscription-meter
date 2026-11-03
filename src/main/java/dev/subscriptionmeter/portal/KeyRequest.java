package dev.subscriptionmeter.portal;

import java.time.Instant;

public record KeyRequest(String id,String accountId,String username,String displayName,String state,
                         String reason,int rpm,int concurrency,String memberId,Instant requestedAt,Instant decidedAt) {}
