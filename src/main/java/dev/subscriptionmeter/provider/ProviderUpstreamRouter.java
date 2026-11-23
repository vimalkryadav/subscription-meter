package dev.subscriptionmeter.provider;

import dev.subscriptionmeter.upstream.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.Map;

@Component @Primary
public final class ProviderUpstreamRouter implements UpstreamTransport {
    private final JdkUpstreamTransport anthropic;
    private final ObjectProvider<OpenAIPlanTransport> openai;
    private final dev.subscriptionmeter.routing.FallbackRouter fallback;
    public ProviderUpstreamRouter(JdkUpstreamTransport anthropic,ObjectProvider<OpenAIPlanTransport> openai,dev.subscriptionmeter.routing.FallbackRouter fallback){
        this.anthropic=anthropic;this.openai=openai;this.fallback=fallback;
    }
    public Reply send(String method,String path,Map<String,String> headers,byte[] body)throws IOException,InterruptedException{
        if(path.startsWith("/openai/v1/")){
            var transport=openai.getIfAvailable();if(transport==null)throw new IOException("OpenAI plan routing is disabled");
            return transport.send(method,path.substring("/openai".length()),headers,body);
        }
        // Anthropic routes go through the fallback router, which strips internal x-meter-* headers.
        return fallback.route(anthropic,method,path,headers,body);
    }
}
