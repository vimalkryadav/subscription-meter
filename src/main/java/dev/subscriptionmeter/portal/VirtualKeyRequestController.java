package dev.subscriptionmeter.portal;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Uses the existing portal and admin session/CSRF filters; bootstrap credentials are not accepted. */
@RestController
public class VirtualKeyRequestController {
    private static final String MEMBER = "/portal/api/virtual-key-requests";
    private static final String ADMIN = "/admin/virtual-key-requests";
    private static final int MAX_BODY_BYTES = 4096;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final VirtualKeyRequests requests;
    private final DirectVirtualKeys keys;

    public VirtualKeyRequestController(VirtualKeyRequests requests, DirectVirtualKeys keys) { this.requests = requests; this.keys = keys; }

    @GetMapping(MEMBER)
    public VirtualKeyRequests.Status status(HttpServletRequest request) {
        return requests.status(actor(request));
    }

    @PostMapping(path = MEMBER, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public VirtualKeyRequests.Status submit(HttpServletRequest request) throws IOException {
        String accountId = actor(request);
        JsonNode reason = field(request, "reason");
        if (!reason.isString()) throw invalid();
        String value = reason.asString().strip();
        if (value.isBlank() || value.length() > 500 || value.indexOf('\0') >= 0) throw invalid();
        return requests.submit(accountId, value);
    }

    @GetMapping(ADMIN)
    public List<VirtualKeyRequests.Review> review(@RequestParam(defaultValue = "pending") String state, HttpServletRequest request) {
        return requests.review(actor(request), state);
    }

    @PostMapping(path = ADMIN + "/{id}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VirtualKeyRequests.Request decide(@PathVariable String id, HttpServletRequest request) throws IOException {
        String accountId = actor(request);
        if (id.length() != 36) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        JsonNode approve = field(request, "approve");
        if (!approve.isBoolean()) throw invalid();
        return requests.decide(accountId, id, approve.asBoolean(), request);
    }

    @GetMapping(MEMBER + "/embedding-usage")
    public List<DirectVirtualKeys.EmbeddingUsage> embeddingUsage(HttpServletRequest request) {
        return keys.embeddingUsage(actor(request));
    }

    @GetMapping(MEMBER + "/key")
    public Map<String, String> key(HttpServletRequest request) {
        return Map.of("key", keys.reveal(actor(request)));
    }

    @PostMapping(path = ADMIN + "/{id}/provision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> provision(@PathVariable String id, HttpServletRequest request) throws IOException {
        String admin = actor(request);
        var body = object(request);
        var budget = body.path("weeklyBudgetUsd");
        var rpm = body.path("rpm");
        var models = body.path("allowedModels");
        if (body.size()!=3 || !budget.isNumber() || !rpm.isIntegralNumber() || !rpm.canConvertToInt()
                || rpm.asInt()<1 || rpm.asInt()>120 || !models.isArray() || models.isEmpty() || models.size()>20) throw invalid();
        var cap = budget.decimalValue();
        if (cap.scale()>2 || cap.compareTo(new java.math.BigDecimal("0.01"))<0 || cap.compareTo(new java.math.BigDecimal("100"))>0) throw invalid();
        var allow = new java.util.LinkedHashSet<String>();
        for (var model : models) {
            if (!model.isString() || !model.asString().matches("[A-Za-z0-9._:-]{1,100}")) throw invalid();
            allow.add(model.asString());
        }
        keys.provision(id, admin, cap, rpm.asInt(), List.copyOf(allow), request);
        return Map.of("state", "active");
    }

    @PostMapping(path = ADMIN + "/{id}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> revoke(@PathVariable String id, HttpServletRequest request) throws IOException {
        actor(request);
        if (!object(request).isEmpty()) throw invalid();
        keys.revoke(id, request);
        return Map.of("state", "revoked");
    }

    private static String actor(HttpServletRequest request) {
        // Only the filters set this trusted attribute, after session (and for writes, CSRF) verification.
        if (request.getAttribute(PortalAuthFilter.ACCOUNT) instanceof String accountId) return accountId;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
    }

    private static JsonNode field(HttpServletRequest request, String name) throws IOException {
        JsonNode body = object(request);
        if (body.size() != 1 || !body.has(name)) throw invalid();
        return body.get(name);
    }

    private static JsonNode object(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw tooLarge();
        try {
            JsonNode body = JSON.readTree(bytes);
            if (body == null || !body.isObject()) throw invalid();
            return body;
        } catch (JacksonException ex) {
            throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid form fields");
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Request is too large");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", ex.getReason() == null ? "Request failed" : ex.getReason()));
    }
}
