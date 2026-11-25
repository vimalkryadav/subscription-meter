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

    public VirtualKeyRequestController(VirtualKeyRequests requests) { this.requests = requests; }

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

    private static String actor(HttpServletRequest request) {
        // Only the filters set this trusted attribute, after session (and for writes, CSRF) verification.
        if (request.getAttribute(PortalAuthFilter.ACCOUNT) instanceof String accountId) return accountId;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
    }

    private static JsonNode field(HttpServletRequest request, String name) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw tooLarge();
        try {
            JsonNode body = JSON.readTree(bytes);
            if (body == null || !body.isObject() || body.size() != 1 || !body.has(name)) throw invalid();
            return body.get(name);
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
