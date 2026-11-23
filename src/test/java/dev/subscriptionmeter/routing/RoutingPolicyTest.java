package dev.subscriptionmeter.routing;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RoutingPolicyTest {
    static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    static final DeploymentHealth.View IDLE = new DeploymentHealth.View(0, null, 0, Instant.EPOCH, Instant.EPOCH, null);

    static Deployment dep(String id, String kind, int priority, int weight) {
        return new Deployment(id, id, kind, id, priority, weight, true, null, null, null);
    }

    static List<String> ids(List<Deployment> list) { return list.stream().map(Deployment::id).toList(); }

    @Test void priorityAlwaysWinsAndDisabledOrBlockedAreDropped() {
        var deployments = List.of(dep("key", "broker", 2, 1000), dep("claude", "anthropic", 1, 100), dep("codex", "codex", 1, 100),
                new Deployment("off", "off", "codex", "off", 1, 100, false, null, null, null));
        var blocked = new DeploymentHealth.View(0, null, 0, Instant.EPOCH, NOW.plusSeconds(60), "quota");
        var order = RoutingPolicy.order(deployments, new RoutingPolicy.Request(false, 10, null), "balanced",
                d -> new RoutingPolicy.Signals(d.id().equals("codex") ? blocked : IDLE, null, true), NOW);
        assertThat(ids(order)).containsExactly("claude", "key");
    }

    @Test void strategiesOrderWithinAPriority() {
        var claude = dep("claude", "anthropic", 1, 100); var codex = dep("codex", "codex", 1, 100);
        var fastCodex = new DeploymentHealth.View(0, 800.0, 0, Instant.EPOCH, Instant.EPOCH, null);
        var slowClaude = new DeploymentHealth.View(4, 9_000.0, 0, Instant.EPOCH, Instant.EPOCH, null);
        java.util.function.Function<Deployment, RoutingPolicy.Signals> signals =
                d -> new RoutingPolicy.Signals(d == claude ? slowClaude : fastCodex, d == claude ? 20.0 : 30.0, true);
        var request = new RoutingPolicy.Request(false, 10, null);
        assertThat(ids(RoutingPolicy.order(List.of(claude, codex), request, "claude_first", signals, NOW))).containsExactly("claude", "codex");
        assertThat(ids(RoutingPolicy.order(List.of(claude, codex), request, "latency", signals, NOW))).containsExactly("codex", "claude");
        assertThat(ids(RoutingPolicy.order(List.of(claude, codex), request, "least_busy", signals, NOW))).containsExactly("codex", "claude");
        // The session's current deployment beats any score.
        assertThat(ids(RoutingPolicy.order(List.of(claude, codex), new RoutingPolicy.Request(false, 10, "claude"), "latency", signals, NOW)))
                .containsExactly("claude", "codex");
    }

    @Test void weightsErrorsAndExplicitModelsAreRespected() {
        var heavy = dep("heavy", "codex", 1, 300); var light = dep("light", "codex", 1, 100); var claude = dep("claude", "anthropic", 1, 100);
        var flaky = new DeploymentHealth.View(0, null, 0.8, Instant.EPOCH, Instant.EPOCH, "HTTP 503");
        var order = RoutingPolicy.order(List.of(light, heavy, claude), new RoutingPolicy.Request(true, 10, null), "balanced",
                d -> new RoutingPolicy.Signals(IDLE, null, true), NOW);
        assertThat(ids(order)).containsExactly("heavy", "light"); // an explicit OpenAI model never goes to Claude
        var withErrors = RoutingPolicy.order(List.of(light, heavy), new RoutingPolicy.Request(true, 10, null), "balanced",
                d -> new RoutingPolicy.Signals(d == heavy ? flaky : IDLE, null, true), NOW);
        assertThat(ids(withErrors)).containsExactly("light", "heavy");
    }

    @Test void contextWindowAndModelSupportFilter() {
        var small = new Deployment("small", "small", "codex", "small", 1, 100, true, null, 1000, null);
        var big = dep("big", "codex", 1, 100);
        var order = RoutingPolicy.order(List.of(small, big), new RoutingPolicy.Request(true, 5000, null), "balanced",
                d -> new RoutingPolicy.Signals(IDLE, null, true), NOW);
        assertThat(ids(order)).containsExactly("big");
        var none = RoutingPolicy.order(List.of(small, big), new RoutingPolicy.Request(true, 10, null), "balanced",
                d -> new RoutingPolicy.Signals(IDLE, null, false), NOW);
        assertThat(none).isEmpty();
    }
}
