package com.codecritic.analysis.impl;

import com.codecritic.analysis.BugDetector;
import com.codecritic.dto.BugFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Best-effort SpotBugs detector. Adds richer findings when the environment
 * has a JDK and the spotbugs CLI available; otherwise returns empty.
 */
@Component
public class SpotBugsBugDetector implements BugDetector {

    private static final Logger log = LoggerFactory.getLogger(SpotBugsBugDetector.class);

    private final SpotBugsRunner spotBugsRunner;

    public SpotBugsBugDetector(SpotBugsRunner spotBugsRunner) {
        this.spotBugsRunner = spotBugsRunner;
    }

    @Override
    public List<BugFinding> detect(String code) {
        try {
            List<String> lines = spotBugsRunner.run(code);
            return lines.stream()
                    .map(line -> new BugFinding("SpotBugsFinding", 0, line, "See SpotBugs output"))
                    .toList();
        } catch (Exception e) {
            log.info("SpotBugs analysis skipped or failed: {}", e.getMessage());
            return List.of();
        }
    }
}
