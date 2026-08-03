package com.codecritic.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisMetricsTest {

    @Test
    void record_accumulatesCountsAndLatency() {
        AnalysisMetrics metrics = new AnalysisMetrics();
        metrics.record("complexity", 10);
        metrics.record("complexity", 30);
        metrics.record("bugs", 5);

        var snapshot = metrics.snapshot();
        @SuppressWarnings("unchecked")
        var analysis = (java.util.Map<String, Object>) snapshot.get("analysis");
        @SuppressWarnings("unchecked")
        var complexity = (java.util.Map<String, Object>) analysis.get("complexity");

        assertEquals(2L, complexity.get("requests"));
        assertEquals(20L, complexity.get("avgLatencyMs"));
        assertEquals(2L, complexity.get("samples"));
        assertTrue(analysis.containsKey("bugs"));
    }

    @Test
    void recordSpotBugsRun_accumulatesDurations() {
        AnalysisMetrics metrics = new AnalysisMetrics();
        metrics.recordSpotBugsRun(100);
        metrics.recordSpotBugsRun(300);

        @SuppressWarnings("unchecked")
        var spotbugs = (java.util.Map<String, Object>) metrics.snapshot().get("spotbugs");
        assertEquals(2L, spotbugs.get("runs"));
        assertEquals(400L, spotbugs.get("totalMs"));
        assertEquals(200L, spotbugs.get("avgMs"));
    }

    @Test
    void snapshot_emptyIsWellFormed() {
        AnalysisMetrics metrics = new AnalysisMetrics();
        var snapshot = metrics.snapshot();
        assertTrue(snapshot.containsKey("analysis"));
        @SuppressWarnings("unchecked")
        var spotbugs = (java.util.Map<String, Object>) snapshot.get("spotbugs");
        assertEquals(0L, spotbugs.get("runs"));
    }
}


