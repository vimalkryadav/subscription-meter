package dev.subscriptionmeter.audit;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Body pieces stored once per member, and the reader that turns a stored row back into its body. */
public final class CaptureBlobStore {
    public static final int FORMAT_INLINE = 1, FORMAT_MANIFEST = 2;
    private static final int CHUNK = 200;
    private final JdbcTemplate jdbc;

    private Boolean postgres;

    public CaptureBlobStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private boolean postgres() {
        if (postgres == null) postgres = Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) ->
                c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres")));
        return postgres;
    }

    /** Inserts the pieces this member does not have yet. Returns the characters newly stored. */
    public long save(String memberId, List<CaptureBodies.Piece> pieces) {
        Set<String> existing = new HashSet<>();
        List<String> keys = pieces.stream().map(CaptureBodies.Piece::key).toList();
        for (int from = 0; from < keys.size(); from += CHUNK) {
            var part = keys.subList(from, Math.min(keys.size(), from + CHUNK));
            List<Object> args = new ArrayList<>(part.size() + 1);
            args.add(memberId);
            args.addAll(part);
            existing.addAll(jdbc.queryForList("SELECT blob_key FROM capture_blobs WHERE member_id=? AND blob_key IN ("
                    + String.join(",", Collections.nCopies(part.size(), "?")) + ")", String.class, args.toArray()));
        }
        long added = 0;
        for (var piece : pieces) {
            if (existing.contains(piece.key())) continue;
            // A key is the hash of its data, so a concurrent insert of the same key holds identical content.
            String insert = postgres()
                    ? "INSERT INTO capture_blobs(member_id,blob_key,data,chars) VALUES(?,?,?,?) ON CONFLICT (member_id,blob_key) DO NOTHING"
                    : "MERGE INTO capture_blobs(member_id,blob_key,data,chars) KEY(member_id,blob_key) VALUES(?,?,?,?)";
            added += jdbc.update(insert, memberId, piece.key(), piece.data(), piece.chars()) == 1 ? piece.chars() : 0;
        }
        return added;
    }

    public Map<String, String> load(String memberId, Collection<String> keys) {
        Map<String, String> found = new HashMap<>();
        List<String> all = List.copyOf(keys);
        for (int from = 0; from < all.size(); from += CHUNK) {
            var part = all.subList(from, Math.min(all.size(), from + CHUNK));
            List<Object> args = new ArrayList<>(part.size() + 1);
            args.add(memberId);
            args.addAll(part);
            jdbc.query("SELECT blob_key,data FROM capture_blobs WHERE member_id=? AND blob_key IN ("
                    + String.join(",", Collections.nCopies(part.size(), "?")) + ")",
                    rs -> { found.put(rs.getString(1), rs.getString(2)); }, args.toArray());
        }
        return found;
    }

    /** The stored request body: the inline text, or the body rebuilt from its manifest. */
    public String body(String memberId, Object format, String inline, String manifest) {
        if (inline != null) return inline;
        if (manifest == null || format == null || ((Number) format).intValue() != FORMAT_MANIFEST) return null;
        return CaptureBodies.rebuild(manifest, load(memberId, CaptureBodies.keys(manifest)));
    }

    /** Replaces the manifest columns of a selected row with its resolved {@code request_body}. */
    public void resolve(Map<String, Object> row, String memberColumn) {
        Object member = row.get(memberColumn);
        if (row.containsKey("request_body") && member != null)
            row.put("request_body", body(member.toString(), row.get("body_format"), (String) row.get("request_body"), (String) row.get("body_manifest")));
        row.remove("body_manifest");
        row.remove("body_format");
    }
}
