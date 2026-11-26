package dev.subscriptionmeter.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Converts finished inline request bodies into deduplicated pieces plus a manifest.
 *
 * <p>Nothing is lost: a row's inline body is cleared only in {@code compact} mode, and only after the body
 * rebuilt from the pieces read back from the database equals the original exactly. Any mismatch or error
 * leaves the inline body untouched. {@code shadow} mode writes pieces and manifests but never clears anything.
 */
@Component
public class CaptureCompactor {
    public static final int CHECK_MATCH = 1, CHECK_MISMATCH = 2, CHECK_ERROR = 3;
    private static final Logger log = LoggerFactory.getLogger(CaptureCompactor.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CaptureBlobStore blobs;
    private final MeterRegistry metrics;
    private final String mode;
    private final int batch;
    private final Duration minAge;

    public CaptureCompactor(JdbcTemplate jdbc, TransactionTemplate tx, MeterRegistry metrics,
                            @Value("${meter.capture.compactor.mode:shadow}") String mode,
                            @Value("${meter.capture.compactor.batch:20}") int batch,
                            @Value("${meter.capture.compactor.min-age-seconds:300}") long minAgeSeconds) {
        this.mode = mode.toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("off", "shadow", "compact").contains(this.mode)) throw new IllegalArgumentException("Invalid compactor mode");
        if (batch < 1 || batch > 500 || minAgeSeconds < 0) throw new IllegalArgumentException("Invalid compactor bounds");
        this.jdbc = jdbc; this.tx = tx; this.metrics = metrics; this.blobs = new CaptureBlobStore(jdbc);
        this.batch = batch; this.minAge = Duration.ofSeconds(minAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${meter.capture.compactor.interval-ms:10000}", initialDelayString = "${meter.capture.compactor.initial-delay-ms:60000}")
    public void scheduled() {
        try { runBatch(); }
        catch (RuntimeException e) { log.warn("Capture compaction delayed ({})", e.getClass().getSimpleName()); }
    }

    /** Processes up to one batch, newest first. Returns the number of rows examined. */
    public int runBatch() {
        if ("off".equals(mode)) return 0;
        // Shadow verifies unchecked rows; compact also converts rows shadow already verified.
        String checks = "compact".equals(mode) ? "(body_check IS NULL OR body_check=" + CHECK_MATCH + ")" : "body_check IS NULL";
        var rows = jdbc.queryForList("SELECT request_id,started_at,member_id FROM request_details WHERE body_format="
                + CaptureBlobStore.FORMAT_INLINE + " AND " + checks + " AND request_body IS NOT NULL AND capture_state<>'pending'"
                + " AND started_at<? ORDER BY started_at DESC LIMIT ?", Timestamp.from(Instant.now().minus(minAge)), batch);
        for (var row : rows) {
            try { tx.executeWithoutResult(status -> process(row)); }
            catch (RuntimeException e) {
                metrics.counter("meter.capture.compactor.rows", "result", "error").increment();
                jdbc.update("UPDATE request_details SET body_check=? WHERE request_id=? AND started_at=? AND body_format=?",
                        CHECK_ERROR, row.get("request_id"), row.get("started_at"), CaptureBlobStore.FORMAT_INLINE);
            }
        }
        return rows.size();
    }

    private void process(Map<String, Object> row) {
        Object id = row.get("request_id"), started = row.get("started_at");
        String member = row.get("member_id").toString();
        var bodies = jdbc.queryForList("SELECT request_body FROM request_details WHERE request_id=? AND started_at=? AND body_format=?",
                String.class, id, started, CaptureBlobStore.FORMAT_INLINE);
        if (bodies.isEmpty() || bodies.getFirst() == null) return;
        String body = bodies.getFirst();
        var split = CaptureBodies.split(member, body);
        long added = blobs.save(member, split.pieces());
        // Verify against what the database actually holds, not the in-memory pieces.
        boolean match = body.equals(CaptureBodies.rebuild(split.manifest(), blobs.load(member, CaptureBodies.keys(split.manifest()))));
        metrics.counter("meter.capture.compactor.rows", "result", match ? "match" : "mismatch").increment();
        metrics.counter("meter.capture.compactor.body.chars").increment(split.chars());
        metrics.counter("meter.capture.compactor.new.chars").increment(added);
        if (match && "compact".equals(mode)) {
            // The only place an inline body is cleared: after an exact, database-verified rebuild.
            jdbc.update("UPDATE request_details SET body_manifest=?,body_chars=?,body_check=?,body_format=?,request_body=NULL"
                    + " WHERE request_id=? AND started_at=? AND body_format=? AND LENGTH(request_body)=?",
                    split.manifest(), split.chars(), CHECK_MATCH, CaptureBlobStore.FORMAT_MANIFEST, id, started,
                    CaptureBlobStore.FORMAT_INLINE, split.chars());
        } else {
            jdbc.update("UPDATE request_details SET body_manifest=?,body_chars=?,body_check=? WHERE request_id=? AND started_at=? AND body_format=?",
                    split.manifest(), split.chars(), match ? CHECK_MATCH : CHECK_MISMATCH, id, started, CaptureBlobStore.FORMAT_INLINE);
        }
    }
}
