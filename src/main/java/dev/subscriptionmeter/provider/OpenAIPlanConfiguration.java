package dev.subscriptionmeter.provider;

import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="meter.openai.enabled",havingValue="true")
public class OpenAIPlanConfiguration {
    @Bean(destroyMethod="close") OpenAIPlanTransport openAIPlanTransport(
            @Value("${meter.openai.auth-mode:unconfigured}")String mode,
            @Value("${meter.openai.origin:https://api.openai.com}")String origin,
            @Value("${meter.openai.token-file:}")String file,
            @Value("${meter.openai.timeout-seconds:300}")int timeout){
        if(file.isBlank())throw new IllegalArgumentException("A dedicated SIWC token file must be configured");
        return new OpenAIPlanTransport(new OpenAIPlanCredentialFile(Path.of(file)),mode,origin,timeout);
    }
}
