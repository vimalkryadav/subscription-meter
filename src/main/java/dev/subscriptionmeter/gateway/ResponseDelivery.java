package dev.subscriptionmeter.gateway;

import java.io.IOException;
import java.util.Map;

/** Tracks delivery separately from prepared headers. A successful write is not proof of client receipt. */
final class ResponseDelivery {
    static final class DownstreamFailure extends IOException {
        DownstreamFailure(IOException cause) { super("Downstream write failed", cause); }
    }
    private final ResponseSink sink;
    private final GatewayMetrics metrics;
    private final long started;
    private boolean wrote, firstByte;
    ResponseDelivery(ResponseSink sink, GatewayMetrics metrics, long started) {
        this.sink=sink;this.metrics=metrics;this.started=started;
    }
    void start(int status, Map<String,String> headers) { sink.start(status,headers); }
    boolean committed() { return wrote || sink.committed(); }
    void prepareError(int status) { if(!committed())sink.prepareError(status); }
    void write(byte[] bytes,int length) throws IOException {
        long before=System.nanoTime();
        try {
            sink.write(bytes,length);wrote=true;
            if(length>0&&!firstByte){firstByte=true;metrics.phase(GatewayMetrics.Phase.FIRST_FORWARD,started);}
        } catch(IOException failure) { throw new DownstreamFailure(failure); }
        finally { metrics.phase(GatewayMetrics.Phase.DOWNSTREAM_WRITE,before); }
    }
}
