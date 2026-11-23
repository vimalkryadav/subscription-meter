package dev.subscriptionmeter.summaries;

import dev.subscriptionmeter.administration.AdminActionAudit;
import dev.subscriptionmeter.portal.PortalAuthFilter;
import dev.subscriptionmeter.portal.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Administrator pages for the key broker. Every change is forwarded with the administrator's
 * identity and also recorded in the gateway's admin action log. The real OpenAI key never passes here.
 */
@RestController
@RequestMapping("/admin/broker")
public class BrokerAdminController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final BrokerAdminClient broker;
    private final AdminActionAudit audit;
    private final SessionService sessions;

    public BrokerAdminController(BrokerAdminClient broker, AdminActionAudit audit, SessionService sessions) {
        this.broker = broker; this.audit = audit; this.sessions = sessions;
    }

    private String actor(HttpServletRequest request) {
        return PortalAuthFilter.cookie(request) == null ? "bootstrap" : sessions.actor(request);
    }

    private ResponseEntity<String> relay(BrokerAdminClient.Reply reply) {
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    private static String id(String value) {
        if (!value.matches("[0-9a-f-]{36}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return value;
    }

    private static Map<String, Object> details(JsonNode body) {
        var safe = new LinkedHashMap<String, Object>();
        for (String field : List.of("name", "purpose", "memberId", "weeklyBudgetUsd", "rpm", "allowedModels", "maxOutputTokens", "expiresAt", "active",
                "weeklyCapUsd", "model", "inputUsdPerMTok", "cachedInputUsdPerMTok", "outputUsdPerMTok"))
            if (body.has(field)) safe.put(field, body.get(field).toString());
        return safe;
    }

    private void record(HttpServletRequest request, BrokerAdminClient.Reply reply, String action, String target, JsonNode body) {
        if (reply.status() >= 200 && reply.status() < 300) audit.record(request, action, target, details(body));
    }

    @GetMapping("/state") public ResponseEntity<String> state(HttpServletRequest request) { return relay(broker.call("GET", "/admin/state", null, actor(request))); }

    @GetMapping("/calls") public ResponseEntity<String> calls(@RequestParam(defaultValue = "") String keyId, @RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        if ((!keyId.isEmpty() && !keyId.matches("[0-9a-f-]{36}")) || limit < 1 || limit > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return relay(broker.call("GET", "/admin/calls?limit=" + limit + (keyId.isEmpty() ? "" : "&keyId=" + URLEncoder.encode(keyId, StandardCharsets.UTF_8)), null, actor(request)));
    }

    /** Shows a virtual key again. Recorded in both the gateway's and the broker's admin logs. */
    @GetMapping("/keys/{keyId}/secret")
    public ResponseEntity<String> reveal(@PathVariable String keyId, HttpServletRequest request) {
        var reply = broker.call("GET", "/admin/keys/" + id(keyId) + "/secret", null, actor(request));
        record(request, reply, "broker.key.reveal", keyId, JSON.createObjectNode());
        return relay(reply);
    }

    @GetMapping("/log") public ResponseEntity<String> log(HttpServletRequest request) { return relay(broker.call("GET", "/admin/log?limit=50", null, actor(request))); }

    @PostMapping(value = "/keys", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(@RequestBody JsonNode body, HttpServletRequest request) {
        var reply = broker.call("POST", "/admin/keys", JSON.writeValueAsString(body), actor(request));
        String target = "new";
        try { target = JSON.readTree(reply.body()).path("id").asString("new"); } catch (RuntimeException ignored) { }
        record(request, reply, "broker.key.create", target, body);
        return relay(reply);
    }

    @PatchMapping(value = "/keys/{keyId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> update(@PathVariable String keyId, @RequestBody JsonNode body, HttpServletRequest request) {
        var reply = broker.call("PATCH", "/admin/keys/" + id(keyId), JSON.writeValueAsString(body), actor(request));
        record(request, reply, body.path("active").asBoolean(true) ? "broker.key.update" : "broker.key.revoke", keyId, body);
        return relay(reply);
    }

    @PutMapping(value = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> settings(@RequestBody JsonNode body, HttpServletRequest request) {
        var reply = broker.call("PUT", "/admin/settings", JSON.writeValueAsString(body), actor(request));
        record(request, reply, "broker.cap.update", "settings", body);
        return relay(reply);
    }

    @PutMapping(value = "/prices", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> price(@RequestBody JsonNode body, HttpServletRequest request) {
        var reply = broker.call("PUT", "/admin/prices", JSON.writeValueAsString(body), actor(request));
        record(request, reply, "broker.price.update", body.path("model").asString("unknown"), body);
        return relay(reply);
    }

    @DeleteMapping("/prices/{model}")
    public ResponseEntity<String> deletePrice(@PathVariable String model, HttpServletRequest request) {
        if (!model.matches("[A-Za-z0-9._:-]{1,100}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var reply = broker.call("DELETE", "/admin/prices/" + model, null, actor(request));
        record(request, reply, "broker.price.delete", model, JSON.createObjectNode());
        return relay(reply);
    }
}
