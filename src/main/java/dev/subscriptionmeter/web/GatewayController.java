package dev.subscriptionmeter.web;

import dev.subscriptionmeter.gateway.*;
import dev.subscriptionmeter.audit.*;
import dev.subscriptionmeter.security.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class GatewayController {
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final MemberService members;
    private final GatewayService gateway;
    private final dev.subscriptionmeter.limits.RequestBodyBudget bodyBudget;
    public GatewayController(MemberService members, GatewayService gateway) { this(members,gateway,new dev.subscriptionmeter.limits.RequestBodyBudget()); }
    @org.springframework.beans.factory.annotation.Autowired
    public GatewayController(MemberService members, GatewayService gateway,dev.subscriptionmeter.limits.RequestBodyBudget bodyBudget) {
        this.members=members;this.gateway=gateway;this.bodyBudget=bodyBudget;
    }
    @PostMapping({"/v1/messages", "/v1/messages/count_tokens"})
    public void post(HttpServletRequest request, HttpServletResponse response) throws IOException { forward(request, response); }
    @GetMapping("/v1/models")
    public void get(HttpServletRequest request, HttpServletResponse response) throws IOException { forward(request, response); }
    void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var member = members.authenticate(AdminAuthFilter.credential(request));
        request.setAttribute(GatewayAccessFilter.MEMBER,member.id());
        try(var reservation=bodyBudget.reserve(request.getContentLengthLong())) {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        String model = "unknown";
        if (request.getMethod().equals("POST")) {
            try {
                JsonNode root = JSON.readTree(body);
                if (root == null || !root.isObject()) throw new IllegalArgumentException();
                if(request.getServletPath().equals("/openai/v1/responses"))dev.subscriptionmeter.provider.OpenAIPlanRequest.validate(root);
                model = root.path("model").asString("unknown");
                model = model.substring(0, Math.min(200, model.length()));
            } catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON object required"); }
        }
        Map<String, String> headers = new HashMap<>();
        for (String name : List.of("content-type", "accept", "anthropic-version", "anthropic-beta", "user-agent")) {
            String value = request.getHeader(name); if (value != null) headers.put(name, value);
        }
        gateway.forward(member, (AuditContext)request.getAttribute(GatewayAccessFilter.CONTEXT), model, headers, body,
                new ResponseSink() {
                    private final Map<String,java.util.List<String>> prior = new HashMap<>();
                    public boolean committed() { return response.isCommitted(); }
                    public void prepareError(int status) {
                        if(response.isCommitted())return;
                        response.resetBuffer();
                        prior.forEach((name,values)->{
                            response.setHeader(name,null);
                            for(String value:values)response.addHeader(name,value);
                        });
                        response.setHeader("Cache-Control","no-store");
                        if(status==503)response.setHeader("Retry-After","10");
                        response.setStatus(status);
                    }
                    public void start(int status, Map<String, String> outgoing) {
                        outgoing.keySet().forEach(name->prior.put(name,java.util.List.copyOf(response.getHeaders(name))));
                        response.setStatus(status); outgoing.forEach(response::setHeader);
                    }
                    public void write(byte[] data, int length) throws IOException {
                        response.getOutputStream().write(data, 0, length); response.flushBuffer();
                    }
                });
        }
    }
}
