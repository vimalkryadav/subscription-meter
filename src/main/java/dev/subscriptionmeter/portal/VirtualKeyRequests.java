package dev.subscriptionmeter.portal;

import dev.subscriptionmeter.administration.AdminActionAudit;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Separate from gateway membership and broker credentials. No approval path issues a key. */
@Service
public class VirtualKeyRequests {
    private static final int REVIEW_LIMIT = 50;
    private static final RowMapper<Request> REQUEST_ROW = (rs, row) -> new Request(rs.getString("id"),
            rs.getString("state"), rs.getString("reason"), rs.getObject("requested_at", OffsetDateTime.class),
            rs.getObject("decided_at", OffsetDateTime.class));
    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final AdminActionAudit audit;

    public VirtualKeyRequests(JdbcTemplate jdbc, AccountRepository accounts, AdminActionAudit audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
    }

    public record Request(String id, String state, String reason, OffsetDateTime requestedAt, OffsetDateTime decidedAt) {}
    public record Status(String state, Request request) {}
    public record Review(String username, String displayName, Request request) {}

    public Status status(String accountId) {
        requireRole(accountId, "member");
        return statusFor(accountId);
    }

    @Transactional
    public Status submit(String accountId, String reason) {
        // Serializes submissions for the account, including the first insert (no request row yet).
        if (!"member".equals(accounts.lock(accountId).role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String id = UUID.randomUUID().toString();
        var current = byAccount(accountId);
        if (current.isEmpty()) {
            jdbc.update("INSERT INTO virtual_key_requests (id, account_id, state, reason) VALUES (?, ?, 'pending', ?)",
                    id, accountId, reason);
        } else if (current.get().state().equals("rejected")) {
            // Replace rather than append. A stale admin tab must not be able to review this new submission.
            jdbc.update("""
                    UPDATE virtual_key_requests SET id=?, state='pending', reason=?,
                      requested_at=CURRENT_TIMESTAMP, decided_at=NULL WHERE account_id=? AND state='rejected'
                    """, id, reason, accountId);
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A request is already pending or awaiting provisioning");
        }
        return statusFor(accountId);
    }

    public List<Review> review(String adminId, String state) {
        requireRole(adminId, "admin");
        String order = switch (state) {
            case "pending" -> "r.requested_at, r.id";
            case "approved_awaiting_provisioning", "rejected" -> "r.decided_at DESC, r.id DESC";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request state");
        };
        return jdbc.query("""
                SELECT r.id, r.state, r.reason, r.requested_at, r.decided_at, a.username, a.display_name
                FROM virtual_key_requests r JOIN portal_accounts a ON a.id=r.account_id
                WHERE r.state=? ORDER BY %s LIMIT ?
                """.formatted(order), (rs, row) -> new Review(rs.getString("username"), rs.getString("display_name"),
                REQUEST_ROW.mapRow(rs, row)), state, REVIEW_LIMIT);
    }

    @Transactional
    public Request decide(String adminId, String id, boolean approve, HttpServletRequest actor) {
        requireRole(adminId, "admin");
        String state = approve ? "approved_awaiting_provisioning" : "rejected";
        int changed = jdbc.update("""
                UPDATE virtual_key_requests SET state=?, decided_at=CURRENT_TIMESTAMP WHERE id=? AND state='pending'
                """, state, id);
        var result = jdbc.query("SELECT * FROM virtual_key_requests WHERE id=?", REQUEST_ROW, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "The request has already been reviewed");
        audit.record(actor, approve ? "virtual_key_request.approve" : "virtual_key_request.reject", id, Map.of("state", state));
        return result;
    }

    private Status statusFor(String accountId) {
        return byAccount(accountId).map(request -> new Status(request.state(), request)).orElseGet(() -> new Status("none", null));
    }

    private Optional<Request> byAccount(String accountId) {
        return jdbc.query("SELECT * FROM virtual_key_requests WHERE account_id=?", REQUEST_ROW, accountId).stream().findFirst();
    }

    private void requireRole(String accountId, String role) {
        if (!accounts.byId(accountId).map(account -> account.role().equals(role)).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
