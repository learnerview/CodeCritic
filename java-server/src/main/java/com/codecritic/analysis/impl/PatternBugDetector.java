package com.codecritic.analysis.impl;

import com.codecritic.analysis.BugDetector;
import com.codecritic.dto.BugFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fast, deterministic pattern-based bug detector.
 *
 * Detects obvious risks without any external tooling so results are always
 * available even in constrained environments.
 */
@Component
public class PatternBugDetector implements BugDetector {

    @Override
    public List<BugFinding> detect(String code) {
        List<BugFinding> bugs = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return bugs;
        }
        String[] lines = code.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.matches(".*\\/\\s*0.*")) {
                bugs.add(new BugFinding("DivisionByZeroRisk", i + 1,
                        "Possible division by zero", "Check divisor for zero"));
            }
            if (line.contains(".toString()") && !line.contains("!= null") && !line.contains("Objects.toString")) {
                bugs.add(new BugFinding("NullPointerRisk", i + 1,
                        "Calling toString() might NPE if obj is null",
                        "Add null check or use String.valueOf()"));
            }
        }
        return bugs;
    }
}
