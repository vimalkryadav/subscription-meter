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
    public ProviderUpstreamRouter(JdkUpstreamTransport anthropic,ObjectProvider<OpenAIPlanTransport> openai){this.anthropic=anthropic;this.openai=openai;}
    public Reply send(String method,String path,Map<String,String> headers,byte[] body)throws IOException,InterruptedException{
        if(path.startsWith("/openai/v1/")){
            var transport=openai.getIfAvailable();if(transport==null)throw new IOException("OpenAI plan routing is disabled");
            return transport.send(method,path.substring("/openai".length()),headers,body);
        }
        return anthropic.send(method,path,headers,body);
    }
}
