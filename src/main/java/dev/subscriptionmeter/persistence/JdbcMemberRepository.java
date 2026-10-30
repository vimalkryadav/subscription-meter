package dev.subscriptionmeter.persistence;

import dev.subscriptionmeter.domain.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMemberRepository implements MemberRepository {
    private final JdbcTemplate jdbc;
    public JdbcMemberRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Member map(ResultSet rs, int row) throws SQLException {
        return new Member(rs.getString("id"), rs.getString("name"), rs.getString("key_prefix"),
                rs.getBoolean("active"), rs.getInt("rpm"), rs.getInt("concurrency_limit"));
    }
    public void add(Member m, String hash) {
        jdbc.update("INSERT INTO members(id,name,key_hash,key_prefix,active,rpm,concurrency_limit) VALUES(?,?,?,?,?,?,?)",
                m.id(), m.name(), hash, m.keyPrefix(), m.active(), m.rpm(), m.concurrency());
    }
    public Optional<Member> findActiveByKeyHash(String hash) {
        return jdbc.query("SELECT * FROM members WHERE key_hash=? AND active=TRUE", this::map, hash).stream().findFirst();
    }
    public List<Member> list() { return jdbc.query("SELECT * FROM members ORDER BY created_at", this::map); }
    public boolean revoke(String id) { return jdbc.update("UPDATE members SET active=FALSE WHERE id=?", id) > 0; }
}
