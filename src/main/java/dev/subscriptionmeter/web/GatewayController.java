package dev.subscriptionmeter.web;

import dev.subscriptionmeter.gateway.*;
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
    public GatewayController(MemberService members, GatewayService gateway) { this.members = members; this.gateway = gateway; }
    @PostMapping({"/v1/messages", "/v1/messages/count_tokens"})
    public void post(HttpServletRequest request, HttpServletResponse response) throws IOException { forward(request, response); }
    @GetMapping("/v1/models")
    public void get(HttpServletRequest request, HttpServletResponse response) throws IOException { forward(request, response); }
    private void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var member = members.authenticate(AdminAuthFilter.credential(request));
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        String model = "unknown";
        if (request.getMethod().equals("POST")) {
            try {
                JsonNode root = JSON.readTree(body);
                if (root == null || !root.isObject()) throw new IllegalArgumentException();
                model = root.path("model").asString("unknown");
                model = model.substring(0, Math.min(200, model.length()));
            } catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON object required"); }
        }
        Map<String, String> headers = new HashMap<>();
        for (String name : List.of("content-type", "accept", "anthropic-version", "anthropic-beta", "user-agent")) {
            String value = request.getHeader(name); if (value != null) headers.put(name, value);
        }
        gateway.forward(member, request.getMethod(), request.getServletPath(), request.getQueryString(), model, headers, body,
                new ResponseSink() {
                    public void start(int status, Map<String, String> outgoing) {
                        response.setStatus(status); outgoing.forEach(response::setHeader);
                    }
                    public void write(byte[] data, int length) throws IOException {
                        response.getOutputStream().write(data, 0, length); response.flushBuffer();
                    }
                });
    }
}
