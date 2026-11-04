package dev.subscriptionmeter.provider;

import dev.subscriptionmeter.usage.*;
import java.util.*;

public final class GatewayProtocols {
    private GatewayProtocols() { }
    private static final ProviderProtocol ANTHROPIC=new ProviderProtocol(){
        public String id(){return "anthropic";}
        public UsageObserver observer(boolean streaming){return new AnthropicUsageObserver(streaming);}
        public boolean inference(String path){return path.equals("/v1/messages");}
        public boolean responseHeader(String name){return Set.of("content-type","request-id","retry-after").contains(name)||name.startsWith("anthropic-ratelimit-");}
        public String requestId(Map<String,String> headers){return headers.getOrDefault("request-id","");}
    };
    private static final ProviderProtocol OPENAI=new ProviderProtocol(){
        public String id(){return "openai";}
        public UsageObserver observer(boolean streaming){return new OpenAIUsageObserver(streaming);}
        public boolean inference(String path){return path.equals("/openai/v1/responses");}
        public boolean responseHeader(String name){return Set.of("content-type","x-request-id","retry-after").contains(name)||name.startsWith("x-ratelimit-");}
        public String requestId(Map<String,String> headers){return headers.getOrDefault("x-request-id","");}
    };
    public static ProviderProtocol forPath(String path){return path.startsWith("/openai/v1/")?OPENAI:ANTHROPIC;}
}
