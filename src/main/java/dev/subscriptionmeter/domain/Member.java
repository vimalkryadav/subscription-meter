package dev.subscriptionmeter.domain;

public record Member(String id, String name, String keyPrefix, boolean active, int rpm, int concurrency) {}
