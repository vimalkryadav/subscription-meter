package dev.subscriptionmeter.portal;

import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Bounds password hashing work and failed-login attempts for this single instance. */
@Component
public class AuthThrottle {
    private final Map<String,Integer> attempts=new HashMap<>();
    private final Semaphore hashing=new Semaphore(4);
    private long window;
    private int total;
    public synchronized AutoCloseable acquire(String username) {
        long minute=System.currentTimeMillis()/60000;
        if(minute!=window) { window=minute; total=0; attempts.clear(); }
        int count=attempts.getOrDefault(username,0);
        if(total>=120 || count>=10 || !hashing.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many attempts. Try again in a minute.");
        total++; attempts.put(username,count+1);
        return hashing::release;
    }
}
