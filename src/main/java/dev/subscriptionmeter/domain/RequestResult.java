package dev.subscriptionmeter.domain;

public record RequestResult(String state, int status, long durationMs, String upstreamId, Usage usage) {}
