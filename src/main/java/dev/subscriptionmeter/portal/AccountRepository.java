package dev.subscriptionmeter.portal;

import java.util.Optional;

public interface AccountRepository {
    void add(Account account);
    Optional<Account> byUsername(String username);
    Optional<Account> byId(String id);
    Account lock(String id);
}
