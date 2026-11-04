package dev.subscriptionmeter.provider;

import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAIPlanTransportTest {
    private static final byte[] BODY="{\"model\":\"fixture-model\",\"store\":false,\"stream\":true,\"input\":[{\"role\":\"user\",\"content\":\"🌏\"}]}".getBytes(StandardCharsets.UTF_8);
    @TempDir Path temp;
    @SuppressWarnings("unchecked") private static HttpResponse<InputStream> reply(int status,InputStream stream){
        HttpResponse<InputStream> reply=mock(HttpResponse.class);
        when(reply.statusCode()).thenReturn(status);when(reply.body()).thenReturn(stream);
        when(reply.headers()).thenReturn(HttpHeaders.of(Map.of("content-type",List.of("text/event-stream")),(k,v)->true));return reply;
    }
    @Test void sendsOnlyDedicatedTokenAndCanonicalHeadersPreservingBodyAndQuotaFailure()throws Exception{
        HttpClient client=mock(HttpClient.class);InputStream response=spy(new ByteArrayInputStream("data: {\"type\":\"response.failed\"}\n\n".getBytes(StandardCharsets.UTF_8)));
        when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(i->reply(429,response));
        try(var transport=new OpenAIPlanTransport(()->"test.oauth-token","siwc-plan","https://api.openai.com",5,client)){
            try(var result=transport.send("POST","/v1/responses",Map.of("Authorization","Bearer sm_member","Cookie","private","OpenAI-Organization","member-choice","Accept","application/json","Content-Type","text/plain","User-Agent","fixture-client"),BODY)){
                assertThat(result.status()).isEqualTo(429);assertThat(result.body().readAllBytes()).isEqualTo("data: {\"type\":\"response.failed\"}\n\n".getBytes(StandardCharsets.UTF_8));
            }
            var request=ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(1)).send(request.capture(),any(HttpResponse.BodyHandler.class));
            var sent=request.getValue();assertThat(sent.uri().toString()).isEqualTo("https://api.openai.com/v1/responses");
            assertThat(sent.timeout()).contains(Duration.ofSeconds(5));
            assertThat(sent.headers().map()).containsOnlyKeys("Authorization","Accept","Accept-Encoding","Content-Type","User-Agent");
            assertThat(sent.headers().firstValue("Authorization")).contains("Bearer test.oauth-token");
            assertThat(sent.headers().firstValue("Accept")).contains("text/event-stream");
            var collector=new ByteArrayOutputStream();var done=new CompletableFuture<Void>();
            sent.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>(){
                public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}
                public void onNext(java.nio.ByteBuffer b){byte[] bytes=new byte[b.remaining()];b.get(bytes);collector.writeBytes(bytes);}
                public void onError(Throwable t){done.completeExceptionally(t);}public void onComplete(){done.complete(null);}
            });done.get(1,TimeUnit.SECONDS);assertThat(collector.toByteArray()).isEqualTo(BODY);verify(response).close();
        }
    }
    @Test void rereadsRotatedFileAndNeverUsesCallerOrCachedCredential()throws Exception{
        Path token=temp.resolve("token");Files.writeString(token,"first.oauth\n");
        HttpClient client=mock(HttpClient.class);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(i->reply(200,new ByteArrayInputStream(new byte[0])));
        try(var transport=new OpenAIPlanTransport(new OpenAIPlanCredentialFile(token),"siwc-plan","https://api.openai.com",5,client)){
            try(var result=transport.send("GET","/v1/models",Map.of(),new byte[0])){assertThat(result.status()).isEqualTo(200);}
            Path next=temp.resolve("next");Files.writeString(next,"second.oauth\r\n");Files.move(next,token,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var result=transport.send("GET","/v1/models",Map.of(),new byte[0])){assertThat(result.status()).isEqualTo(200);}
            var request=ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(2)).send(request.capture(),any(HttpResponse.BodyHandler.class));
            assertThat(request.getAllValues()).extracting(r->r.headers().firstValue("Authorization").orElseThrow()).containsExactly("Bearer first.oauth","Bearer second.oauth");
        }
    }
    @Test void malformedMissingAndOversizedCredentialFilesFailWithoutNetwork()throws Exception{
        Path token=temp.resolve("token");HttpClient client=mock(HttpClient.class);
        try(var transport=new OpenAIPlanTransport(new OpenAIPlanCredentialFile(token),"siwc-plan","https://api.openai.com",5,client)){
            assertThatThrownBy(()->transport.send("GET","/v1/models",Map.of(),new byte[0])).isInstanceOf(IOException.class);
            for(String invalid:List.of(""," ","Bearer test","sk-api-key","sm_member","token\nsecond","token\t"," token","x".repeat(16385),"é")){
                Files.writeString(token,invalid);
                assertThatThrownBy(()->transport.send("GET","/v1/models",Map.of(),new byte[0])).isInstanceOf(IOException.class);
            }
            verifyNoInteractions(client);
        }
    }
    @Test void queryAndUnknownPathsFailBeforeCredentialRead()throws Exception{
        HttpClient client=mock(HttpClient.class);
        try(var transport=new OpenAIPlanTransport(()->{throw new AssertionError("Must not read credentials");},"siwc-plan","https://api.openai.com",5,client)){
            for(String path:List.of("/v1/responses?x=1","/v1/responses/other","https://other.invalid/v1/responses"))
                assertThatThrownBy(()->transport.send("POST",path,Map.of(),BODY)).isInstanceOf(IOException.class);
            verifyNoInteractions(client);
        }
    }
    @Test void deadlineClosesBlockedResponseStream()throws Exception{
        var closed=new CountDownLatch(1);InputStream body=new ByteArrayInputStream(new byte[0]){public void close(){closed.countDown();}};
        HttpClient client=mock(HttpClient.class);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(i->reply(200,body));
        try(var transport=new OpenAIPlanTransport(()->"test.oauth","siwc-plan","https://api.openai.com",1,client);var result=transport.send("POST","/v1/responses",Map.of(),BODY)){
            assertThat(closed.await(3,TimeUnit.SECONDS)).isTrue();
        }
    }
}
