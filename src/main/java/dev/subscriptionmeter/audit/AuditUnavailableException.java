package dev.subscriptionmeter.audit;

/** Audit durability was not established; strict forwarding must stop. */
public class AuditUnavailableException extends RuntimeException {
    public AuditUnavailableException(String message) { super(message); }
    public AuditUnavailableException(String message, Throwable cause) { super(message, cause); }
}
