package dev.subscriptionmeter.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface AnalyticsReader {
    List<Map<String, Object>> summarize(Instant since);
}
