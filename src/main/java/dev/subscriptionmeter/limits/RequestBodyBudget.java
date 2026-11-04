package dev.subscriptionmeter.limits;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounds retained request bytes before allocation/parsing, including chunked bodies. */
@Component
public class RequestBodyBudget {
    public static final int MAX_BODY=16*1024*1024;
    private static final int UNIT=1024;
    private final Semaphore available=new Semaphore(64*1024,true);
    public RequestLimiter.Permit reserve(long contentLength) {
        if(contentLength>MAX_BODY)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        int units=(int)Math.max(1,((contentLength<0?MAX_BODY:contentLength)+UNIT-1)/UNIT);
        if(!available.tryAcquire(units))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Request byte budget exhausted; retry later");
        var closed=new AtomicBoolean();
        return ()->{if(closed.compareAndSet(false,true))available.release(units);};
    }
}
