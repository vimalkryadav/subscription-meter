package dev.subscriptionmeter.portal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshSessionRepository {
    public record Access(String family,String accountId,String csrf) {}
    public record Refresh(String family,String accountId,String csrf,Instant created,Instant used,String receipt) {}
    private final JdbcTemplate jdbc;
    public RefreshSessionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Access> access(String hash,Instant now){
        var rows=jdbc.query("SELECT a.session_hash,s.account_id,f.csrf_token FROM portal_access_tokens a JOIN portal_refresh_families f ON f.session_hash=a.session_hash JOIN portal_sessions s ON s.token_hash=f.session_hash WHERE a.token_hash=? AND a.expires_at>?",
            (r,n)->new Access(r.getString(1),r.getString(2),r.getString(3)),hash,Timestamp.from(now));
        return rows.stream().findFirst();
    }
    public Optional<String> csrf(String hash){return jdbc.queryForList("SELECT f.csrf_token FROM portal_access_tokens a JOIN portal_refresh_families f ON f.session_hash=a.session_hash WHERE a.token_hash=?",String.class,hash).stream().findFirst();}
    public Optional<String> familyForAccess(String hash){return jdbc.queryForList("SELECT session_hash FROM portal_access_tokens WHERE token_hash=?",String.class,hash).stream().findFirst();}
    public Optional<String> familyForRefresh(String hash){return jdbc.queryForList("SELECT session_hash FROM portal_refresh_tokens WHERE token_hash=?",String.class,hash).stream().findFirst();}
    public void create(String family,String csrf){jdbc.update("INSERT INTO portal_refresh_families(session_hash,csrf_token) VALUES(?,?)",family,csrf);}
    public Optional<Refresh> lock(String hash){
        // Lock the anchor first: refresh and administrator DELETE serialize on the same row.
        var family=familyForRefresh(hash);if(family.isEmpty())return Optional.empty();
        if(jdbc.queryForList("SELECT token_hash FROM portal_sessions WHERE token_hash=? FOR UPDATE",String.class,family.get()).isEmpty())return Optional.empty();
        return jdbc.query("SELECT t.session_hash,s.account_id,f.csrf_token,t.created_at,t.used_at,t.receipt FROM portal_refresh_tokens t JOIN portal_refresh_families f ON f.session_hash=t.session_hash JOIN portal_sessions s ON s.token_hash=f.session_hash WHERE t.token_hash=?",
            (r,n)->new Refresh(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant(),r.getTimestamp(5)==null?null:r.getTimestamp(5).toInstant(),r.getString(6)),hash).stream().findFirst();
    }
    public void issue(String family,String accessHash,String refreshHash,String receipt,Instant now){
        jdbc.update("INSERT INTO portal_access_tokens(token_hash,session_hash,expires_at) VALUES(?,?,?)",accessHash,family,Timestamp.from(now.plusSeconds(900)));
        jdbc.update("INSERT INTO portal_refresh_tokens(token_hash,session_hash,created_at,receipt) VALUES(?,?,?,?)",refreshHash,family,Timestamp.from(now),receipt);
        jdbc.update("DELETE FROM portal_access_tokens WHERE session_hash=? AND expires_at<=?",family,Timestamp.from(now));
        jdbc.update("DELETE FROM portal_refresh_tokens WHERE session_hash=? AND used_at<?",family,Timestamp.from(now.minusSeconds(30L*86400)));
    }
    public void consume(String hash,String receipt,Instant now){jdbc.update("UPDATE portal_refresh_tokens SET used_at=?,receipt=? WHERE token_hash=?",Timestamp.from(now),receipt,hash);}
    public boolean touch(String family,Instant now){return jdbc.update("UPDATE portal_sessions SET last_seen_at=?,expires_at=? WHERE token_hash=?",Timestamp.from(now),Timestamp.from(Instant.parse("9999-12-31T00:00:00Z")),family)==1;}
    public void delete(String family){jdbc.update("DELETE FROM portal_sessions WHERE token_hash=?",family);}
}
