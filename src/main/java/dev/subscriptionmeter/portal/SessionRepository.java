package dev.subscriptionmeter.portal;

import java.time.Instant;
import java.util.Optional;

public interface SessionRepository {
    void add(String hash,String accountId,Instant now,Instant expires);
    Optional<String> authenticate(String hash,Instant now,Instant idleCutoff);
    void delete(String hash);
    void prune(String accountId,Instant now,Instant idleCutoff);
}
