package com.codecritic.exec;

import java.util.ArrayList;
import java.util.List;

public record TestSuiteResult(
        String revision,
        String adapter,
        String command,
        ExecutionStatus status,
        int testsRun,
        int failures,
        int errors,
        int skipped,
        long durationMillis,
        List<TestFailure> failedTests,
        String outputTail,
        boolean outputTruncated,
        List<String> warnings
) {
    public TestSuiteResult {
        failedTests = List.copyOf(failedTests);
        warnings = List.copyOf(warnings);
        outputTail = outputTail == null ? "" : outputTail;
    }

    public boolean hasTestFailures() {
        return failures > 0 || errors > 0;
    }

    public static Builder builder(
            String revision,
            String adapter,
            String command,
            ExecutionStatus status) {
        return new Builder(revision, adapter, command, status);
    }

    public static final class Builder {
        private final String revision;
        private final String adapter;
        private final String command;
        private final ExecutionStatus status;
        private int testsRun;
        private int failures;
        private int errors;
        private int skipped;
        private long durationMillis;
        private List<TestFailure> failedTests = List.of();
        private String outputTail = "";
        private boolean outputTruncated;
        private List<String> warnings = List.of();

        private Builder(
                String revision,
                String adapter,
                String command,
                ExecutionStatus status) {
            this.revision = revision;
            this.adapter = adapter;
            this.command = command;
            this.status = status;
        }

        public Builder counts(int testsRun, int failures, int errors, int skipped) {
            this.testsRun = testsRun;
            this.failures = failures;
            this.errors = errors;
            this.skipped = skipped;
            return this;
        }

        public Builder durationMillis(long durationMillis) {
            this.durationMillis = durationMillis;
            return this;
        }

        public Builder failedTests(List<TestFailure> failedTests) {
            this.failedTests = failedTests == null ? List.of() : new ArrayList<>(failedTests);
            return this;
        }

        public Builder output(String outputTail, boolean outputTruncated) {
            this.outputTail = outputTail;
            this.outputTruncated = outputTruncated;
            return this;
        }

        public Builder warnings(List<String> warnings) {
            this.warnings = warnings == null ? List.of() : new ArrayList<>(warnings);
            return this;
        }

        public TestSuiteResult build() {
            return new TestSuiteResult(
                    revision,
                    adapter,
                    command,
                    status,
                    testsRun,
                    failures,
                    errors,
                    skipped,
                    durationMillis,
                    failedTests,
                    outputTail,
                    outputTruncated,
                    warnings);
        }
    }
}
