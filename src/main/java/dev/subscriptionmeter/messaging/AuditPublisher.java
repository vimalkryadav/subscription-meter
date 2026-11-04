package dev.subscriptionmeter.messaging;

import java.util.List;

public interface AuditPublisher {
    /** Returns only after every part has been acknowledged; throws on uncertainty. */
    void publish(List<AuditOperation> operations);
}
