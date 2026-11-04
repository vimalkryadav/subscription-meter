package dev.subscriptionmeter.messaging;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Internal-only health/metrics endpoint with no request content or member identifiers. */
public final class AuditWorkerTelemetry implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor=new java.util.concurrent.ThreadPoolExecutor(1,1,0,
            java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(8));
    private final AtomicLong projected=new AtomicLong(),projectedBytes=new AtomicLong(),lastPoll=new AtomicLong(),lag=new AtomicLong(-1);
    private volatile boolean assigned,databaseHealthy;
    public AuditWorkerTelemetry(String bind,int port)throws IOException{
        server=HttpServer.create(new InetSocketAddress(bind,port),8);server.setExecutor(executor);
        server.createContext("/health/readiness",exchange->{
            boolean ready=ready();byte[] body=(ready?"ready\n":"unavailable\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ready?200:503,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.createContext("/metrics",exchange->{
            String body="control_usage_audit_worker_projected_records_total "+projected.get()+"\n"
                    +"control_usage_audit_worker_projected_bytes_total "+projectedBytes.get()+"\n"
                    +"control_usage_audit_worker_last_poll_timestamp_seconds "+lastPoll.get()+"\n"
                    +"control_usage_audit_worker_records_lag "+lag.get()+"\n"
                    +"control_usage_audit_worker_ready "+(ready()?1:0)+"\n";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
    }
    public void polled(boolean assigned){this.assigned=assigned;lastPoll.set(Instant.now().getEpochSecond());}
    public void databaseHealthy(boolean value){databaseHealthy=value;}
    public void projected(int bytes){projected.incrementAndGet();projectedBytes.addAndGet(bytes);}
    public void lag(long value){lag.set(value);}
    public boolean ready(){return assigned&&databaseHealthy&&Instant.now().getEpochSecond()-lastPoll.get()<20;}
    public void close(){server.stop(0);executor.shutdownNow();}
}
