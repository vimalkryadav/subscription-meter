package dev.subscriptionmeter.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-side request investigation, independent of capture delivery. */
public interface RequestQuery {
    record Filter(Instant from, Instant to, String memberId, String model, String requestId,
                  String upstreamRequestId, String state, Integer status, String captureState,
                  Long minDurationMs, Long maxDurationMs, Boolean usageSeen, String provider,
                  String upstreamAccount, String clientFamily, String keyPrefix, String category) {}
    record Position(Instant startedAt, String id) {}
    record Page(List<Map<String,Object>> items, String nextCursor, Instant asOf,
                Instant from, Instant to, Instant generatedAt) {}
    List<Map<String,Object>> search(Filter filter, Position before, int limit);
    Optional<Map<String,Object>> detail(String id);
    List<Map<String,Object>> events(String id, Instant startedAt, int after, int through, int limit);
}
