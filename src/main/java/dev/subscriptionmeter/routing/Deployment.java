package dev.subscriptionmeter.routing;

/**
 * One routable upstream: a Claude subscription ("anthropic"), a Codex login ("codex"), or the OpenAI
 * API key behind the broker ("broker"). Lower priority numbers are always preferred; within a priority
 * the routing strategy scores deployments. {@code credentialFile} is null for the primary credentials.
 */
public record Deployment(String id, String name, String kind, String account, int priority, int weight, boolean enabled,
                         Integer maxConcurrency, Integer contextWindowTokens, String credentialFile) {
    public boolean anthropic() { return kind.equals("anthropic"); }
    public boolean codex() { return kind.equals("codex"); }
    public boolean broker() { return kind.equals("broker"); }
    /** Provider label recorded in the request ledger. */
    public String provider() { return anthropic() ? "anthropic" : "openai"; }
}
