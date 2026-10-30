package dev.subscriptionmeter.domain;

public record Usage(long input, long output, long cacheCreation, long cacheRead, boolean seen) {
    public static Usage empty() { return new Usage(0, 0, 0, 0, false); }
}
