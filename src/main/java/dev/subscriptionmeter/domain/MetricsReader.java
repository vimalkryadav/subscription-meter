package dev.subscriptionmeter.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only, bounded operational reporting; independent of the storage engine. */
public interface MetricsReader {
    record Report(Instant from, Instant to, int stepSeconds, String mode,
                  Map<String,Object> summary, List<Map<String,Object>> traffic,
                  List<Map<String,Object>> timings, List<Map<String,Object>> errors,
                  List<Map<String,Object>> topMembers, List<Map<String,Object>> topModels) {}
    Report read(Instant from, Instant to, int stepSeconds, String mode);
}
