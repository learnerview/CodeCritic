package com.codecritic.analysis.impl;

import com.codecritic.analysis.BugDetector;
import com.codecritic.dto.BugFinding;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite bug detector — combines deterministic pattern detection with the
 * optional (cached) SpotBugs pass (Structural pattern).
 */
@Component
@Primary
public class CompositeBugDetector implements BugDetector {

    private final List<BugDetector> detectors;

    public CompositeBugDetector(PatternBugDetector patternBugDetector, CachedSpotBugsBugDetector spotBugsBugDetector) {
        this.detectors = List.of(patternBugDetector, spotBugsBugDetector);
    }

    @Override
    public List<BugFinding> detect(String code) {
        List<BugFinding> all = new ArrayList<>();
        for (BugDetector detector : detectors) {
            all.addAll(detector.detect(code));
        }
        return all;
    }
}
