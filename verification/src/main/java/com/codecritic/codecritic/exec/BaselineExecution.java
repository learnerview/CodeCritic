package com.codecritic.exec;

import java.util.List;

public record BaselineExecution(
        TestSuiteResult base,
        TestSuiteResult head,
        String setupError,
        List<String> warnings,
        DifferentialExecution differential
) {
    public BaselineExecution {
        warnings = List.copyOf(warnings);
    }

    public BaselineExecution(
            TestSuiteResult base,
            TestSuiteResult head,
            String setupError,
            List<String> warnings) {
        this(base, head, setupError, warnings, null);
    }

    public static BaselineExecution completed(
            TestSuiteResult base,
            TestSuiteResult head,
            DifferentialExecution differential) {
        return new BaselineExecution(base, head, null, List.of(), differential);
    }

    public static BaselineExecution setupFailed(String error) {
        return new BaselineExecution(null, null, error, List.of());
    }

    public BaselineExecution withWarnings(List<String> combinedWarnings) {
        return new BaselineExecution(base, head, setupError, combinedWarnings, differential);
    }
}
