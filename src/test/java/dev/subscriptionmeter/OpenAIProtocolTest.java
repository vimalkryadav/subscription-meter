package dev.subscriptionmeter;

import dev.subscriptionmeter.provider.*;
import dev.subscriptionmeter.usage.OpenAIUsageObserver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class OpenAIProtocolTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String VALID="{\"model\":\"account-model\",\"store\":false,\"stream\":true,\"input\":[{\"role\":\"user\",\"content\":\"test\"}]}";
    private void feed(OpenAIUsageObserver observer,String wire){for(byte b:wire.getBytes(StandardCharsets.UTF_8))observer.accept(new byte[]{b},1);}
    @Test void splitToolStreamCountsFinalUsageOnceAndKeepsDetailSubsets(){
        var observer=new OpenAIUsageObserver(true);
        feed(observer,"event: response.function_call_arguments.delta\r\ndata: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"🌏\"}\r\n\r\n");
        assertThat(observer.snapshot().seen()).isFalse();
        String terminal="data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":100,\"output_tokens\":30,\"total_tokens\":130,\"input_tokens_details\":{\"cached_tokens\":40,\"cache_write_tokens\":20,\"cache_write_tokens_details\":{\"ttl_30m_tokens\":12,\"ttl_12h_tokens\":8}},\"output_tokens_details\":{\"reasoning_tokens\":10}}}}\n\n";
        feed(observer,terminal);feed(observer,terminal);
        var usage=observer.snapshot();assertThat(usage.complete()).isTrue();
        assertThat(usage.input()).isEqualTo(40);assertThat(usage.output()).isEqualTo(30);
        assertThat(usage.cacheRead()).isEqualTo(40);assertThat(usage.cacheCreation()).isEqualTo(20);
        assertThat(usage.components()).containsEntry("output_tokens_details.reasoning_tokens",10L).containsEntry("input_tokens",100L);
    }
    @Test void doneMarkerMissingUsageAndQuotaFailureDoNotBecomeSuccess(){
        var observer=new OpenAIUsageObserver(true);feed(observer,"data: [DONE]\n\n");assertThat(observer.complete()).isFalse();
        feed(observer,"data: {\"type\":\"response.completed\",\"response\":{\"usage\":null}}\n\n");
        assertThat(observer.snapshot().seen()).isFalse();assertThat(observer.complete()).isFalse();
        feed(observer,"data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n");
        assertThat(observer.error()).isTrue();assertThat(observer.complete()).isFalse();
    }
    @Test void incompleteAndMalformedUsageRemainPartial(){
        var observer=new OpenAIUsageObserver(true);
        feed(observer,"data: {\"type\":\"response.incomplete\",\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}}\n\n");
        assertThat(observer.snapshot().seen()).isTrue();assertThat(observer.complete()).isFalse();
        var malformed=new OpenAIUsageObserver(true);
        feed(malformed,"data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"input_tokens_details\":{\"cached_tokens\":5}}}}\n\n");
        assertThat(malformed.complete()).isFalse();
    }
    @Test void acceptsExplicitHistoryToolContinuationWithoutMutatingIt(){
        var request=JSON.readTree("{\"model\":\"account-model\",\"store\":false,\"stream\":true,\"input\":[{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"local.read\",\"arguments\":\"{}\"},{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"result\"}],\"tools\":[{\"type\":\"namespace\",\"name\":\"local\",\"tools\":[{\"type\":\"function\",\"name\":\"read\",\"parameters\":{\"type\":\"object\"}}]}]}");
        String before=request.toString();OpenAIPlanRequest.validate(request);assertThat(request.toString()).isEqualTo(before);
    }
    @Test void rejectsStoredOrApiSpecificRequests(){
        assertThatThrownBy(()->OpenAIPlanRequest.validate(JSON.readTree(VALID.replace("\"store\":false","\"store\":true")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OpenAIPlanRequest.validate(JSON.readTree(VALID.replace("\"stream\":true","\"stream\":true,\"previous_response_id\":\"resp_1\"")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disabledConfigurationDoesNotCreateTransportOrReadMissingCredential(){
        new ApplicationContextRunner().withUserConfiguration(OpenAIPlanConfiguration.class)
                .withPropertyValues("meter.openai.enabled=false","meter.openai.token-file=/does-not-exist")
                .run(context->assertThat(context).doesNotHaveBean(OpenAIPlanTransport.class));
    }
    @Test void apiKeysAndUnsupportedOriginsFailBeforeAnyNetworkRequest()throws Exception{
        assertThatThrownBy(()->new OpenAIPlanTransport(()->"unused","api-key","https://api.openai.com",30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OpenAIPlanTransport(()->"unused","siwc-plan","https://chatgpt.com",30)).isInstanceOf(IllegalArgumentException.class);
        try(var transport=new OpenAIPlanTransport(()->"sk-not-a-plan-token","siwc-plan","https://api.openai.com",30)){
            assertThatThrownBy(()->transport.send("POST","/v1/responses",java.util.Map.of(),VALID.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(java.io.IOException.class).hasMessage("A dedicated SIWC OAuth access token is required");
        }
    }
    @Test void selectsProtocolByExplicitRoute(){
        assertThat(GatewayProtocols.forPath("/v1/messages").id()).isEqualTo("anthropic");
        assertThat(GatewayProtocols.forPath("/openai/v1/responses").id()).isEqualTo("openai");
        assertThat(GatewayProtocols.forPath("/openai/v1/responses").inference("/openai/v1/responses")).isTrue();
    }
}
