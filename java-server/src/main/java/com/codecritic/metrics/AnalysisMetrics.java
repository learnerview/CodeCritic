package com.codecritic.metrics;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-memory metrics for the analysis service.
 *
 * <p>Tracks request counts and latency per analysis type (complexity, bugs,
 * test-generation) plus SpotBugs execution statistics. Snapshot is a plain
 * {@code Map} so it can be exposed over HTTP without extra dependencies.</p>
 */
@Component
public class AnalysisMetrics {

    private final ConcurrentHashMap<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TotalCount> latencies = new ConcurrentHashMap<>();
    private final AtomicLong spotBugsRuns = new AtomicLong();
    private final AtomicLong spotBugsTotalMs = new AtomicLong();

    /** Record one completed analysis of the given type (e.g. "complexity"). */
    public void record(String analysisType, long latencyMs) {
        requestCounts.computeIfAbsent(analysisType, k -> new AtomicLong()).incrementAndGet();
        latencies.computeIfAbsent(analysisType, k -> new TotalCount()).add(latencyMs);
    }

    /** Record one SpotBugs execution with its wall-clock duration in ms. */
    public void recordSpotBugsRun(long durationMs) {
        spotBugsRuns.incrementAndGet();
        spotBugsTotalMs.addAndGet(durationMs);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new TreeMap<>();
        out.put("startedAtEpochMs", System.currentTimeMillis());
        Map<String, Object> byType = new TreeMap<>();
        for (Map.Entry<String, AtomicLong> entry : requestCounts.entrySet()) {
            TotalCount tc = latencies.get(entry.getKey());
            byType.put(entry.getKey(), Map.of(
                    "requests", entry.getValue().get(),
                    "avgLatencyMs", tc == null || tc.count() == 0 ? 0 : tc.total() / tc.count(),
                    "samples", tc == null ? 0 : tc.count()));
        }
        out.put("analysis", byType);
        long runs = spotBugsRuns.get();
        out.put("spotbugs", Map.of(
                "runs", runs,
                "totalMs", spotBugsTotalMs.get(),
                "avgMs", runs == 0 ? 0 : spotBugsTotalMs.get() / runs));
        return out;
    }

    private static final class TotalCount {
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong count = new AtomicLong();

        void add(long value) {
            total.addAndGet(value);
            count.incrementAndGet();
        }

        long total() {
            return total.get();
        }

        long count() {
            return count.get();
        }
    }
}
