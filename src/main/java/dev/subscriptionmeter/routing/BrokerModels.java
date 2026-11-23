package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.summaries.BrokerAdminClient;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Models the broker has prices for (it refuses others), refreshed every 5 minutes; empty when unknown. */
@Component
public class BrokerModels {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final BrokerAdminClient broker;
    private volatile Set<String> models;
    private volatile Instant at = Instant.EPOCH;

    public BrokerModels(BrokerAdminClient broker) { this.broker = broker; }

    public Optional<Set<String>> priced() {
        Instant now = Instant.now();
        if (models != null && at.plus(Duration.ofMinutes(5)).isAfter(now)) return Optional.of(models);
        try {
            var reply = broker.call("GET", "/admin/state", null, "system:routing");
            if (reply.status() == 200) {
                var found = new HashSet<String>();
                for (JsonNode p : JSON.readTree(reply.body()).path("prices")) found.add(p.path("model").asString(""));
                models = Set.copyOf(found); at = now;
            }
        } catch (RuntimeException e) { /* broker unreachable: keep the previous list */ }
        return Optional.ofNullable(models);
    }
}
