package dev.subscriptionmeter.provider;

import dev.subscriptionmeter.upstream.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;

/** Disabled unless explicitly configured. Supports the documented public SIWC plan route only. */
public final class OpenAIPlanTransport implements UpstreamTransport,AutoCloseable {
    private final HttpClient client;
    private final ScheduledExecutorService deadlines;
    private final UpstreamCredentialProvider credentials;
    private final int timeout;
    public OpenAIPlanTransport(UpstreamCredentialProvider credentials,String mode,String origin,int timeout){
        if(!mode.equals("siwc-plan")||!origin.equals("https://api.openai.com"))throw new IllegalArgumentException("Only the public SIWC plan route is supported");
        if(timeout<1||timeout>3600)throw new IllegalArgumentException("Invalid OpenAI timeout");
        this.credentials=credentials;this.timeout=timeout;
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        this.deadlines=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).factory());
    }
    public Reply send(String method,String path,Map<String,String> headers,byte[] body)throws IOException,InterruptedException{
        if(!(method.equals("POST")&&path.equals("/v1/responses"))&&!(method.equals("GET")&&path.equals("/v1/models")))
            throw new IOException("Unsupported OpenAI plan route");
        if(method.equals("POST"))OpenAIPlanRequest.validate(JsonMapper.builder().build().readTree(body));
        String token=credentials.bearerToken();
        if(token==null||token.isBlank()||token.length()>16384||token.startsWith("sk-")||token.startsWith("sm_")||token.startsWith("Bearer ")||token.contains("\n")||token.contains("\r"))
            throw new IOException("A dedicated SIWC OAuth access token is required");
        long started=System.nanoTime();
        var request=HttpRequest.newBuilder(URI.create("https://api.openai.com"+path)).timeout(Duration.ofSeconds(timeout))
                .method(method,body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((key,value)->{if(Set.of("content-type","accept","user-agent").contains(key.toLowerCase(Locale.ROOT)))request.header(key,value);});
        request.header("Authorization","Bearer "+token);request.header("Accept-Encoding","identity");
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
        var deadline=deadlines.schedule(()->{try{response.body().close();}catch(IOException ignored){}},
                Math.max(0,TimeUnit.SECONDS.toNanos(timeout)-(System.nanoTime()-started)),TimeUnit.NANOSECONDS);
        return new Reply(){
            public int status(){return response.statusCode();}
            public Map<String,List<String>> headers(){return response.headers().map();}
            public InputStream body(){return response.body();}
            public void close()throws IOException{deadline.cancel(false);response.body().close();}
        };
    }
    public void close(){deadlines.shutdownNow();client.close();}
}
