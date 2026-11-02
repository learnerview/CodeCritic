package com.codecritic.exec;

import java.util.List;

public record ParsedTestResults(
        int suiteFiles,
        int testsRun,
        int failures,
        int errors,
        int skipped,
        List<TestFailure> failedTests,
        List<String> warnings
) {
    public ParsedTestResults {
        failedTests = List.copyOf(failedTests);
        warnings = List.copyOf(warnings);
    }

    public static ParsedTestResults empty() {
        return new ParsedTestResults(0, 0, 0, 0, 0, List.of(), List.of());
    }
}
