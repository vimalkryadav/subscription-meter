package dev.subscriptionmeter.portal;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPortalRepository implements AccountRepository, SessionRepository, KeyRequestRepository {
    private final JdbcTemplate jdbc;
    public JdbcPortalRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private Account account(ResultSet rs,int row) throws SQLException {
        return new Account(rs.getString("id"),rs.getString("username"),rs.getString("display_name"),rs.getString("password_hash"),rs.getString("role"));
    }
    public void add(Account a) {
        jdbc.update("INSERT INTO portal_accounts(id,username,display_name,password_hash) VALUES(?,?,?,?)",
                a.id(),a.username(),a.displayName(),a.passwordHash());
    }
    public Optional<Account> byUsername(String username) {
        return jdbc.query("SELECT * FROM portal_accounts WHERE username=?",this::account,username).stream().findFirst();
    }
    public Optional<Account> byId(String id) {
        return jdbc.query("SELECT * FROM portal_accounts WHERE id=?",this::account,id).stream().findFirst();
    }
    public Account lock(String id) {
        return jdbc.queryForObject("SELECT * FROM portal_accounts WHERE id=? FOR UPDATE",this::account,id);
    }
    public boolean hasAdmin() { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM portal_accounts WHERE role='admin')",Boolean.class)); }
    public void add(String hash,String accountId,Instant now,Instant expires) {
        jdbc.update("INSERT INTO portal_sessions(token_hash,account_id,last_seen_at,expires_at) VALUES(?,?,?,?)",
                hash,accountId,Timestamp.from(now),Timestamp.from(expires));
    }
    public Optional<String> authenticate(String hash,Instant now,Instant cutoff) {
        var ids=jdbc.queryForList("SELECT account_id FROM portal_sessions WHERE token_hash=? AND expires_at>? AND last_seen_at>?",
                String.class,hash,Timestamp.from(now),Timestamp.from(cutoff));
        if(ids.isEmpty()) return Optional.empty();
        int touched=jdbc.update("UPDATE portal_sessions SET last_seen_at=? WHERE token_hash=? AND expires_at>? AND last_seen_at>?",
                Timestamp.from(now),hash,Timestamp.from(now),Timestamp.from(cutoff));
        return touched==1?Optional.of(ids.getFirst()):Optional.empty();
    }
    public void delete(String hash) { jdbc.update("DELETE FROM portal_sessions WHERE token_hash=?",hash); }
    public void prune(String accountId,Instant now,Instant cutoff) {
        jdbc.update("DELETE FROM portal_sessions WHERE expires_at<=? OR last_seen_at<=?",Timestamp.from(now),Timestamp.from(cutoff));
        var hashes=jdbc.queryForList("SELECT token_hash FROM portal_sessions WHERE account_id=? ORDER BY last_seen_at DESC",String.class,accountId);
        for(int i=4;i<hashes.size();i++) delete(hashes.get(i));
    }
    private static final String REQUESTS="SELECT k.*,a.username,a.display_name FROM portal_key_requests k JOIN portal_accounts a ON a.id=k.account_id ";
    private KeyRequest keyRequest(ResultSet rs,int row) throws SQLException {
        Timestamp decided=rs.getTimestamp("decided_at");
        return new KeyRequest(rs.getString("id"),rs.getString("account_id"),rs.getString("username"),rs.getString("display_name"),
                rs.getString("state"),rs.getString("reason"),rs.getInt("rpm"),rs.getInt("concurrency_limit"),rs.getString("member_id"),
                rs.getTimestamp("requested_at").toInstant(),decided==null?null:decided.toInstant());
    }
    public Optional<KeyRequest> byAccount(String id) {
        return jdbc.query(REQUESTS+"WHERE k.account_id=?",this::keyRequest,id).stream().findFirst();
    }
    public Optional<KeyRequest> byRequestId(String id) {
        return jdbc.query(REQUESTS+"WHERE k.id=?",this::keyRequest,id).stream().findFirst();
    }
    public void submit(String accountId,String reason) {
        int changed=jdbc.update("UPDATE portal_key_requests SET state='pending',reason=?,requested_at=CURRENT_TIMESTAMP,decided_at=NULL WHERE account_id=?",reason,accountId);
        if(changed==0) jdbc.update("INSERT INTO portal_key_requests(id,account_id,state,reason) VALUES(?,?,'pending',?)",UUID.randomUUID().toString(),accountId,reason);
    }
    public void decide(String id,String state,int rpm,int concurrency) {
        jdbc.update("UPDATE portal_key_requests SET state=?,rpm=?,concurrency_limit=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",state,rpm,concurrency,id);
    }
    public void issued(String id,String memberId) {
        jdbc.update("UPDATE portal_key_requests SET state='active',member_id=? WHERE id=?",memberId,id);
    }
    public void revoke(String id) { jdbc.update("UPDATE portal_key_requests SET state='revoked',decided_at=CURRENT_TIMESTAMP WHERE id=?",id); }
    public List<KeyRequest> list() { return jdbc.query(REQUESTS+"ORDER BY k.requested_at DESC LIMIT 500",this::keyRequest); }
    public List<Map<String,Object>> usage(String accountId) {
        return jdbc.queryForList("SELECT r.model,r.state,COUNT(*) AS requests,SUM(input_tokens) AS input_tokens,SUM(output_tokens) AS output_tokens,"+
                "SUM(cache_creation_input_tokens) AS cache_creation_input_tokens,SUM(cache_read_input_tokens) AS cache_read_input_tokens "+
                "FROM requests r JOIN portal_key_requests k ON k.member_id=r.member_id WHERE k.account_id=? AND r.started_at>=? GROUP BY r.model,r.state",
                accountId,Timestamp.from(Instant.now().minusSeconds(30L*86400)));
    }
}
