package dev.subscriptionmeter.routing;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Pure selection logic: filters deployments that cannot serve a request, then orders the rest.
 * Lower priority numbers always come first (so the API key is used only when no subscription can serve);
 * within a priority the session's current deployment comes first, then the strategy's score decides.
 */
public final class RoutingPolicy {
    public static final Set<String> STRATEGIES = Set.of("balanced", "claude_first", "latency", "least_busy");

    /** What the policy needs to know about one deployment right now. */
    public record Signals(DeploymentHealth.View health, Double usedPercent, boolean servesModel) {}

    /** What the policy needs to know about the request. */
    public record Request(boolean explicitOpenAI, long estimatedTokens, String assignedDeployment) {}

    private RoutingPolicy() {}

    /** Higher is better. Every factor is in (0, 1]; weight scales the result. */
    static double score(Deployment d, Signals s, String strategy) {
        double headroom = Math.max(0.01, 1 - (s.usedPercent() == null ? 0 : s.usedPercent()) / 100.0);
        double errors = 1 - Math.min(0.9, s.health().errorRate());
        int inFlight = s.health().inFlight();
        double load = d.maxConcurrency() != null ? Math.max(0.01, 1 - (double) inFlight / d.maxConcurrency()) : 1 / (1 + 0.05 * inFlight);
        double latency = s.health().ttfbMs() == null ? 1 : 1 / (1 + s.health().ttfbMs() / 30_000.0);
        double base = d.weight() / 100.0 * errors;
        return switch (strategy) {
            case "latency" -> base * (s.health().ttfbMs() == null ? 0.5 : 1 / (1 + s.health().ttfbMs() / 2_000.0)) * Math.sqrt(headroom);
            case "least_busy" -> base * (1.0 / (1 + inFlight)) * Math.sqrt(headroom);
            default -> base * headroom * load * latency; // balanced (also the tiebreak for claude_first)
        };
    }

    public static List<Deployment> order(List<Deployment> deployments, Request request, String strategy,
                                         Function<Deployment, Signals> signals, Instant now) {
        var candidates = new ArrayList<Deployment>();
        var saturated = new HashSet<String>();
        var known = new HashMap<String, Signals>();
        for (var d : deployments) {
            if (!d.enabled()) continue;
            if (request.explicitOpenAI() && d.anthropic()) continue; // an OpenAI model was asked for
            var s = signals.apply(d);
            known.put(d.id(), s);
            if (!s.health().usable(now) || !s.servesModel()) continue;
            if (d.contextWindowTokens() != null && request.estimatedTokens() > d.contextWindowTokens()) continue;
            if (d.maxConcurrency() != null && s.health().inFlight() >= d.maxConcurrency()) saturated.add(d.id());
            candidates.add(d);
        }
        Comparator<Deployment> byPriority = Comparator.comparingInt(Deployment::priority);
        // At its concurrency cap a deployment is busy, not unavailable: last within its priority, never behind a lower priority.
        Comparator<Deployment> notSaturated = Comparator.comparing(d -> saturated.contains(d.id()));
        Comparator<Deployment> assignedFirst = Comparator.comparing(d -> !d.id().equals(request.assignedDeployment()));
        Comparator<Deployment> claudeFirst = Comparator.comparing(d -> !(strategy.equals("claude_first") && d.anthropic()));
        Comparator<Deployment> byScore = Comparator.comparingDouble(d -> -score(d, known.get(d.id()), strategy));
        Comparator<Deployment> tiebreak = Comparator.comparing((Deployment d) -> !d.anthropic()).thenComparing(Deployment::id);
        var comparator = byPriority.thenComparing(notSaturated).thenComparing(assignedFirst).thenComparing(claudeFirst).thenComparing(byScore).thenComparing(tiebreak);
        candidates.sort(comparator);
        return candidates;
    }
}
