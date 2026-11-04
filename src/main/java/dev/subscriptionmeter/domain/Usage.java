package dev.subscriptionmeter.domain;

import java.util.Map;

/** Counters preserve the legacy aggregate API; components preserve actual field presence. */
public record Usage(long input, long output, long cacheCreation, long cacheRead, boolean seen,
                    Map<String, Long> components, boolean complete) {
    public Usage { components = Map.copyOf(components); }
    public Usage(long input, long output, long cacheCreation, long cacheRead, boolean seen) {
        this(input, output, cacheCreation, cacheRead, seen, Map.of(), false);
    }
    public static Usage empty() { return new Usage(0, 0, 0, 0, false); }
}
