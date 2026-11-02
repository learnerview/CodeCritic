package com.codecritic.exec;

import java.util.ArrayList;
import java.util.List;

public record DifferentialExecution(
        String testPath,
        String testClass,
        DifferentialVerdict verdict,
        boolean stabilityChecked,
        List<String> warnings,
        TestSuiteResult baseRepeat,
        TestSuiteResult headRepeat
) {
    public DifferentialExecution {
        warnings = List.copyOf(warnings);
    }

    public static Builder builder(
            String testPath,
            String testClass,
            DifferentialVerdict verdict) {
        return new Builder(testPath, testClass, verdict);
    }

    public static final class Builder {
        private final String testPath;
        private final String testClass;
        private final DifferentialVerdict verdict;
        private boolean stabilityChecked;
        private List<String> warnings = List.of();
        private TestSuiteResult baseRepeat;
        private TestSuiteResult headRepeat;

        private Builder(String testPath, String testClass, DifferentialVerdict verdict) {
            this.testPath = testPath;
            this.testClass = testClass;
            this.verdict = verdict;
        }

        public Builder stability(
                boolean stabilityChecked,
                TestSuiteResult baseRepeat,
                TestSuiteResult headRepeat) {
            this.stabilityChecked = stabilityChecked;
            this.baseRepeat = baseRepeat;
            this.headRepeat = headRepeat;
            return this;
        }

        public Builder warnings(List<String> warnings) {
            this.warnings = warnings == null ? List.of() : new ArrayList<>(warnings);
            return this;
        }

        public DifferentialExecution build() {
            return new DifferentialExecution(
                    testPath,
                    testClass,
                    verdict,
                    stabilityChecked,
                    warnings,
                    baseRepeat,
                    headRepeat);
        }
    }
}
