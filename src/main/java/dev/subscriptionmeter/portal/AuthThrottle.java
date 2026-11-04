package dev.subscriptionmeter.portal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Shared attempt budgets, with a separate local bound on expensive password hashing. */
@Component
public class AuthThrottle {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Semaphore hashing=new Semaphore(4);
    public AuthThrottle(JdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(3); tx=new TransactionTemplate(manager); tx.setTimeout(3); }
    public AutoCloseable acquire(String username) {
        if(!hashing.tryAcquire()) throw limited();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT name FROM coordination_locks WHERE name='authentication' FOR UPDATE",String.class);
                long now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class).getTime();
                jdbc.update("DELETE FROM auth_attempts WHERE attempted_at<=?",now-60000);
                String subject=digest(username.strip().toLowerCase(Locale.ROOT));
                int total=jdbc.queryForObject("SELECT COUNT(*) FROM auth_attempts",Integer.class);
                int count=jdbc.queryForObject("SELECT COUNT(*) FROM auth_attempts WHERE subject_hash=?",Integer.class,subject);
                if(total>=120 || count>=10) throw limited();
                jdbc.update("INSERT INTO auth_attempts(id,subject_hash,attempted_at) VALUES(?,?,?)",UUID.randomUUID().toString(),subject,now);
            });
        } catch(RuntimeException e) { hashing.release(); throw e; }
        AtomicBoolean closed=new AtomicBoolean();
        return () -> { if(closed.compareAndSet(false,true)) hashing.release(); };
    }
    private static ResponseStatusException limited() { return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many attempts. Try again in a minute."); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
