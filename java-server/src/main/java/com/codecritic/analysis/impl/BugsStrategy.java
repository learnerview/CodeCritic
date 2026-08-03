package com.codecritic.analysis.impl;

import com.codecritic.analysis.AnalysisStrategy;
import com.codecritic.analysis.AnalysisType;
import com.codecritic.analysis.BugDetector;
import com.codecritic.dto.BugReport;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BugsStrategy implements AnalysisStrategy {

    private final BugDetector bugDetector;

    public BugsStrategy(BugDetector bugDetector) {
        this.bugDetector = bugDetector;
    }

    @Override
    public AnalysisType type() {
        return AnalysisType.BUGS;
    }

    @Override
    public Object execute(Map<String, Object> payload) {
        String code = (String) payload.get("code");
        return new BugReport(bugDetector.detect(code));
    }
}
