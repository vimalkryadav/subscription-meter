package dev.subscriptionmeter.administration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcAdminOperations implements AdminOperations {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public JdbcAdminOperations(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Map<String,Object>> accounts(String search){
        String pattern="%"+search.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return jdbc.queryForList("""
            SELECT a.id,a.username,a.display_name,a.role,a.created_at,k.state AS access_state,k.member_id,
            m.active AS member_active,m.key_prefix
            FROM portal_accounts a LEFT JOIN portal_key_requests k ON k.account_id=a.id LEFT JOIN members m ON m.id=k.member_id
            WHERE LOWER(a.username) LIKE ? ESCAPE '!' OR LOWER(a.display_name) LIKE ? ESCAPE '!'
            ORDER BY a.created_at DESC,a.id DESC LIMIT 100
            """,pattern,pattern);
    }
    private void accountExists(String accountId,boolean lock){
        if(jdbc.queryForList("SELECT id FROM portal_accounts WHERE id=?"+(lock?" FOR UPDATE":""),String.class,accountId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private List<Map<String,Object>> rawSessions(String accountId){
        return jdbc.queryForList("SELECT s.token_hash,s.last_seen_at,s.expires_at,f.session_hash AS persistent FROM portal_sessions s LEFT JOIN portal_refresh_families f ON f.session_hash=s.token_hash WHERE s.account_id=? ORDER BY s.last_seen_at DESC",accountId);
    }
    public List<Map<String,Object>> sessions(String accountId){
        accountExists(accountId,false);Instant now=Instant.now();var result=new ArrayList<Map<String,Object>>();
        for(var row:rawSessions(accountId)){
            var safe=new LinkedHashMap<String,Object>();safe.put("sessionRef",reference((String)row.get("token_hash")));
            safe.put("lastSeenAt",row.get("last_seen_at"));safe.put("expiresAt",row.get("persistent")!=null?null:row.get("expires_at"));safe.put("persistent",row.get("persistent")!=null);
            safe.put("active",row.get("persistent")!=null || instant(row.get("expires_at")).isAfter(now)&&instant(row.get("last_seen_at")).isAfter(now.minusSeconds(3600)));
            result.add(safe);
        }return result;
    }
    @Transactional public int revokeSessions(String accountId,String sessionRef,String actor){
        // Serialize with session creation. This signs out existing sessions; it does not disable future login.
        accountExists(accountId,true);int changed=0;
        if(sessionRef==null)changed=jdbc.update("DELETE FROM portal_sessions WHERE account_id=?",accountId);
        else for(var row:rawSessions(accountId))if(reference((String)row.get("token_hash")).equals(sessionRef))
            changed+=jdbc.update("DELETE FROM portal_sessions WHERE account_id=? AND token_hash=?",accountId,row.get("token_hash"));
        log(actor,sessionRef==null?"sessions.revoke_all":"sessions.revoke",accountId,Map.of("revokedCount",changed));return changed;
    }
    @Transactional public void updateLimits(String memberId,int rpm,int concurrency,int expectedRpm,int expectedConcurrency,String actor){
        if(rpm<1||rpm>600||concurrency<1||concurrency>20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows=jdbc.queryForList("SELECT rpm,concurrency_limit FROM members WHERE id=? FOR UPDATE",memberId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);var before=rows.getFirst();
        if(((Number)before.get("rpm")).intValue()!=expectedRpm||((Number)before.get("concurrency_limit")).intValue()!=expectedConcurrency)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Limits changed; refresh and review the latest values");
        jdbc.update("UPDATE members SET rpm=?,concurrency_limit=? WHERE id=?",rpm,concurrency,memberId);
        log(actor,"member.limits.update",memberId,Map.of("previousRpm",expectedRpm,"previousConcurrency",expectedConcurrency,"rpm",rpm,"concurrency",concurrency));
    }
    public List<Map<String,Object>> actions(String action,int limit){
        return jdbc.queryForList("SELECT id,actor_id,action,target_id,details_json,occurred_at FROM admin_action_log "
                +"WHERE (?='' OR action=?) ORDER BY occurred_at DESC,id DESC LIMIT ?",action,action,limit);
    }
    private void log(String actor,String action,String target,Map<String,?> detail){
        jdbc.update("INSERT INTO admin_action_log(id,actor_id,action,target_id,details_json) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(),actor,action,target,json.writeValueAsString(detail));
    }
    private static String reference(String hash){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(("admin-session-reference-v1:"+hash).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Instant instant(Object value){if(value instanceof java.sql.Timestamp t)return t.toInstant();return ((OffsetDateTime)value).toInstant();}
}
