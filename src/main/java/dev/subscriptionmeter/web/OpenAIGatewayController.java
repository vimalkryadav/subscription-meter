package dev.subscriptionmeter.web;

import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="meter.openai.enabled",havingValue="true")
public final class OpenAIGatewayController {
    private final GatewayController handler;
    public OpenAIGatewayController(GatewayController handler){this.handler=handler;}
    @PostMapping("/openai/v1/responses") public void responses(HttpServletRequest request,HttpServletResponse response)throws IOException{handler.forward(request,response);}
    @GetMapping("/openai/v1/models") public void models(HttpServletRequest request,HttpServletResponse response)throws IOException{handler.forward(request,response);}
}
