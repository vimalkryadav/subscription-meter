package dev.subscriptionmeter.portal;

import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final PasswordHasher passwords;
    private final AuthThrottle throttle;
    private final String dummyHash;
    public AccountService(AccountRepository accounts,PasswordHasher passwords,AuthThrottle throttle) {
        this.accounts=accounts; this.passwords=passwords; this.throttle=throttle;
        dummyHash=passwords.encode(PortalTokens.generate());
    }
    private String normalize(String username) { return username.strip().toLowerCase(Locale.ROOT); }
    public Account register(String username,String name,String password) {
        String login=normalize(username);
        try(var permit=throttle.acquire(login)) {
            var account=new Account(UUID.randomUUID().toString(),login,name.strip(),passwords.encode(password));
            accounts.add(account); return account;
        } catch(DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Username unavailable"); }
        catch(ResponseStatusException e) { throw e; }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public Account login(String username,String password) {
        String login=normalize(username);
        try(var permit=throttle.acquire(login)) {
            var found=accounts.byUsername(login);
            boolean valid=passwords.matches(password,found.map(Account::passwordHash).orElse(dummyHash));
            if(!valid || found.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid username or password");
            return found.get();
        } catch(ResponseStatusException e) { throw e; }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public Account find(String id) {
        return accounts.byId(id).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
