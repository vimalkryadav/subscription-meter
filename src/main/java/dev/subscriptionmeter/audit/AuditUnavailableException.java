package dev.subscriptionmeter.audit;

/** Audit durability was not established; strict forwarding must stop with a retryable status. */
public class AuditUnavailableException extends org.springframework.web.server.ResponseStatusException {
    public AuditUnavailableException(String message) { super(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Audit logging temporarily unavailable"); }
    public AuditUnavailableException(String message, Throwable cause) { super(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Audit logging temporarily unavailable",cause); }
}
