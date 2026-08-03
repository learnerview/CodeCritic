package com.codecritic.analysis.impl;

import com.codecritic.analysis.BugDetector;
import com.codecritic.dto.BugFinding;
import com.codecritic.metrics.AnalysisMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caching decorator for the expensive SpotBugs pass.
 *
 * <p>SpotBugs compiles the submitted source and runs an external subprocess, which
 * dominates analysis latency. Re-analyzing identical source (e.g. the same snippet
 * in consecutive reviews, or repeated files in a repository run) replays the cached
 * result instead of recompiling.</p>
 *
 * <p>Cache key = SHA-256 of the source plus a detector version. Bumping
 * {@link #DETECTOR_VERSION} invalidates all entries whenever detection logic
 * changes. The cache is bounded to cap memory usage on long-running servers.</p>
 */
@Component
public class CachedSpotBugsBugDetector implements BugDetector {

    private static final Logger log = LoggerFactory.getLogger(CachedSpotBugsBugDetector.class);

    private static final String DETECTOR_VERSION = "1";
    private static final int MAX_ENTRIES = 256;

    private final SpotBugsBugDetector delegate;
    private final AnalysisMetrics metrics;
    private final ConcurrentHashMap<String, List<BugFinding>> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public CachedSpotBugsBugDetector(SpotBugsBugDetector delegate, AnalysisMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public List<BugFinding> detect(String code) {
        String key = cacheKey(code);
        List<BugFinding> cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        long started = System.nanoTime();
        List<BugFinding> findings = delegate.detect(code);
        metrics.recordSpotBugsRun((System.nanoTime() - started) / 1_000_000);
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
            log.info("SpotBugs cache reached {} entries; cleared", MAX_ENTRIES);
        }
        cache.put(key, findings);
        return findings;
    }

    /** Number of cached results replayed. */
    public long hits() {
        return hits.get();
    }

    /** Number of underlying SpotBugs executions performed. */
    public long misses() {
        return misses.get();
    }

    /** Current number of cached entries. */
    public int size() {
        return cache.size();
    }

    static String cacheKey(String code) {
        try {
            String material = (code == null ? "" : code) + "|" + DETECTOR_VERSION;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
