package com.codecritic.exec;

import java.util.Locale;
import java.util.regex.Pattern;

public final class TestRunClassifier {

    private static final Pattern TEST_SUMMARY = Pattern.compile(
            "tests run:\\s*\\d+,\\s*failures:\\s*[1-9]\\d*,\\s*errors:\\s*\\d+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST_SUMMARY_ERRORS = Pattern.compile(
            "tests run:\\s*\\d+,\\s*failures:\\s*\\d+,\\s*errors:\\s*[1-9]\\d*",
            Pattern.CASE_INSENSITIVE);

    private TestRunClassifier() {
    }

    public static ExecutionStatus classify(ProcessResult process, ParsedTestResults parsed) {
        if (process.timedOut()) {
            return ExecutionStatus.TIMEOUT;
        }
        if (parsed.failures() > 0 || parsed.errors() > 0) {
            return ExecutionStatus.TEST_FAILURES;
        }
        if (process.exitCode() == 0) {
            return ExecutionStatus.SUCCESS;
        }
        String output = process.stdout() + "\n" + process.stderr();
        String lower = output.toLowerCase(Locale.ROOT);
        if (parsed.suiteFiles() == 0 && looksLikeTestFailure(output, lower)) {
            return ExecutionStatus.TEST_FAILURES;
        }
        if (looksLikeCompileFailure(lower)) {
            return ExecutionStatus.COMPILE_FAILURE;
        }
        return ExecutionStatus.BUILD_FAILURE;
    }

    private static boolean looksLikeTestFailure(String output, String lower) {
        return TEST_SUMMARY.matcher(output).find()
                || TEST_SUMMARY_ERRORS.matcher(output).find()
                || lower.contains("there are test failures")
                || lower.contains("there were failing tests")
                || lower.contains("> task :test failed");
    }

    private static boolean looksLikeCompileFailure(String output) {
        return output.contains("compilation failure")
                || output.contains("compilation error")
                || output.contains("could not compile")
                || output.contains("compile failed")
                || output.contains("cannot find symbol")
                || output.contains("package does not exist");
    }
}
