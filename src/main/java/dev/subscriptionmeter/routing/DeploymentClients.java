package dev.subscriptionmeter.routing;

import dev.subscriptionmeter.upstream.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The client for each deployment: primary credentials use the shared beans; extra accounts get their own. */
@Component
public class DeploymentClients {
    private final CodexTarget primaryCodex;
    private final BrokerTarget broker;
    private final RoutingState state;
    private final QuotaBalancer balancer;
    private final String codexUrl, mode;
    private final int timeout;
    private final Map<String, UpstreamTransport> anthropic = new ConcurrentHashMap<>();
    private final Map<String, CodexTarget> codex = new ConcurrentHashMap<>();

    public DeploymentClients(CodexTarget primaryCodex, BrokerTarget broker, RoutingState state, QuotaBalancer balancer,
                             @Value("${meter.codex.url:https://chatgpt.com/backend-api/codex/responses}") String codexUrl,
                             @Value("${meter.mode}") String mode, @Value("${meter.timeout-seconds}") int timeout) {
        this.primaryCodex = primaryCodex; this.broker = broker; this.state = state; this.balancer = balancer;
        this.codexUrl = codexUrl; this.mode = mode; this.timeout = timeout;
    }

    /** Null means "use the primary Anthropic transport". */
    public UpstreamTransport anthropic(Deployment d) {
        if (d.credentialFile() == null) return null;
        return anthropic.computeIfAbsent(d.id() + "|" + d.credentialFile(), k -> new JdkUpstreamTransport(
                new FileCredentialProvider(mode, d.credentialFile()), "https://api.anthropic.com", mode, timeout));
    }

    public ResponsesTarget openai(Deployment d) {
        if (d.broker()) return broker;
        if (d.credentialFile() == null) return primaryCodex;
        return codex.computeIfAbsent(d.id() + "|" + d.credentialFile(), k -> new CodexTarget(d.credentialFile(), codexUrl, state, balancer, d.account()));
    }

    public boolean servesModel(Deployment d, String upstreamModel, BrokerModels brokerModels) {
        if (d.anthropic()) return true;
        if (d.broker()) return brokerModels.priced().map(m -> m.contains(upstreamModel)).orElse(true);
        var target = (CodexTarget) openai(d);
        return target.available() && target.models().map(m -> m.contains(upstreamModel)).orElse(true);
    }
}
