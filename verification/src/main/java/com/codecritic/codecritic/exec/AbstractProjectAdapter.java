package com.codecritic.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public abstract class AbstractProjectAdapter implements ProjectAdapter {

    private static final int OUTPUT_TAIL_CHARS = 6000;

    private final CommandRunner commandRunner;
    private final TestResultXmlParser resultParser;

    protected AbstractProjectAdapter(CommandRunner commandRunner, TestResultXmlParser resultParser) {
        this.commandRunner = commandRunner;
        this.resultParser = resultParser;
    }

    protected abstract List<String> testCommand(Path worktree);

    protected abstract List<String> targetedTestCommand(Path worktree, String testSelector);

    protected abstract List<Path> testResultRoots(Path worktree);

    protected List<Path> findResultDirectories(
            Path worktree,
            String outputDirectory,
            String... reportDirectoryNames) {
        Set<String> names = Set.of(reportDirectoryNames);
        List<Path> roots = new ArrayList<>();
        try (var paths = Files.walk(worktree)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> {
                        Path parent = path.getParent();
                        return parent != null
                                && outputDirectory.equals(parent.getFileName().toString())
                                && names.contains(path.getFileName().toString());
                    })
                    .forEach(roots::add);
        } catch (IOException | UncheckedIOException ignored) {
            // Discovery is best-effort; missing reports are handled as absent evidence.
        }
        roots.sort(Comparator.comparing(Path::toString));
        return List.copyOf(roots);
    }

    @Override
    public TestSuiteResult execute(Path worktree, String revision, ExecutionOptions options) {
        return execute(worktree, revision, options, null);
    }

    @Override
    public TestSuiteResult execute(
            Path worktree,
            String revision,
            ExecutionOptions options,
            String testSelector) {
        List<String> command = testSelector == null || testSelector.isBlank()
                ? testCommand(worktree)
                : targetedTestCommand(worktree, testSelector);
        String displayCommand = displayCommand(command);

        ProcessResult process;
        try {
            process = commandRunner.run(worktree, command, options.timeout(), options.maxOutputBytes());
        } catch (RuntimeException e) {
            String message = "Test process did not start: " + e.getMessage();
            return TestSuiteResult.builder(revision, name(), displayCommand, ExecutionStatus.ERROR)
                    .output(message, false)
                    .warnings(List.of(message))
                    .build();
        }

        ParsedTestResults parsed;
        try {
            parsed = resultParser.parse(testResultRoots(worktree));
        } catch (RuntimeException e) {
            parsed = new ParsedTestResults(
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of("Unable to parse test results: " + e.getMessage()));
        }
        ExecutionStatus status = TestRunClassifier.classify(process, parsed);
        String combinedOutput = combineOutput(process);
        boolean reportTruncated = combinedOutput.length() > OUTPUT_TAIL_CHARS;

        List<String> warnings = new ArrayList<>(parsed.warnings());
        if (!"host".equals(commandRunner.environment())) {
            warnings.add("Executed inside the Docker sandbox.");
        }
        if (process.stdoutTruncated() || process.stderrTruncated()) {
            warnings.add("Test process output exceeded the capture limit and was truncated.");
        }
        if (reportTruncated) {
            warnings.add("Report shows only the last " + OUTPUT_TAIL_CHARS + " characters of test output.");
        }
        if (process.timedOut()) {
            warnings.add("Test process timed out after " + options.timeout().toSeconds() + " seconds.");
        }
        if (parsed.suiteFiles() == 0
                && (status == ExecutionStatus.TEST_FAILURES || status == ExecutionStatus.BUILD_FAILURE)) {
            warnings.add("No test result XML was found; status was classified from build output.");
        }

        return TestSuiteResult.builder(revision, name(), displayCommand, status)
                .counts(parsed.testsRun(), parsed.failures(), parsed.errors(), parsed.skipped())
                .durationMillis(process.duration().toMillis())
                .failedTests(parsed.failedTests())
                .output(
                        tail(combinedOutput),
                        process.stdoutTruncated() || process.stderrTruncated() || reportTruncated)
                .warnings(warnings)
                .build();
    }

    private static String displayCommand(List<String> command) {
        if (command.size() >= 5
                && "cmd.exe".equalsIgnoreCase(command.get(0))
                && "/c".equalsIgnoreCase(command.get(3))) {
            return String.join(" ", command.subList(4, command.size()));
        }
        return String.join(" ", command);
    }

    private static String combineOutput(ProcessResult process) {
        if (process.stderr().isBlank()) {
            return process.stdout();
        }
        if (process.stdout().isBlank()) {
            return process.stderr();
        }
        return process.stdout() + "\n" + process.stderr();
    }

    private static String tail(String output) {
        if (output.length() <= OUTPUT_TAIL_CHARS) {
            return output;
        }
        return "... (earlier output omitted) ...\n"
                + output.substring(output.length() - OUTPUT_TAIL_CHARS);
    }
}
