package dev.subscriptionmeter.routing;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds each blocking upstream read without timing out downstream backpressure. */
final class IdleResponseBody extends FilterInputStream {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().factory());
    static { TIMER.setRemoveOnCancelPolicy(true); }
    private final AutoCloseable permit;
    private final int seconds;
    private final AtomicBoolean closed = new AtomicBoolean();

    IdleResponseBody(InputStream body, AutoCloseable permit, int seconds) {
        super(body);
        this.permit = permit;
        this.seconds = Math.max(1, seconds);
    }

    @FunctionalInterface private interface Read { int run() throws IOException; }

    private int bounded(Read read) throws IOException {
        var completed = new AtomicBoolean();
        var alarm = TIMER.schedule(() -> {
            if (completed.compareAndSet(false, true)) Thread.startVirtualThread(() -> {
                try { close(); } catch (IOException ignored) { }
            });
        }, seconds, TimeUnit.SECONDS);
        try { return read.run(); }
        finally {
            boolean inTime = completed.compareAndSet(false, true);
            alarm.cancel(false);
            if (!inTime) throw new SocketTimeoutException("Upstream response body stopped sending data");
        }
    }

    @Override public int read() throws IOException { return bounded(in::read); }
    @Override public int read(byte[] bytes, int off, int len) throws IOException {
        return bounded(() -> in.read(bytes, off, len));
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try { super.close(); }
        finally {
            try { permit.close(); }
            catch (Exception e) { throw new IOException("Could not release routing permit", e); }
        }
    }
}
