package com.codecritic.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MavenProjectAdapterTest {

    @TempDir
    Path worktree;

    @Test
    void runsMavenTestAndParsesSuccess() {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        CommandRunner runner = (directory, command, timeout, maxBytes) -> {
            captured.set(List.copyOf(command));
            return result(command, 0, "tests passed", false);
        };
        MavenProjectAdapter adapter = new MavenProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.adapter()).isEqualTo("maven");
        assertThat(result.testsRun()).isZero();
        assertThat(captured.get()).contains("test");
        assertThat(String.join(" ", captured.get())).contains("-B");
        assertThat(result.command()).contains("mvn").doesNotStartWith("cmd.exe");
    }

    @Test
    void runsOnlyTheSelectedTest() {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        CommandRunner runner = (directory, command, timeout, maxBytes) -> {
            captured.set(List.copyOf(command));
            return result(command, 0, "tests passed", false);
        };
        MavenProjectAdapter adapter = new MavenProjectAdapter(runner, new TestResultXmlParser());

        adapter.execute(
                worktree,
                "HEAD",
                ExecutionOptions.defaults(),
                "com.example.CalculatorTest");

        assertThat(String.join(" ", captured.get()))
                .contains("-Dtest=com.example.CalculatorTest")
                .endsWith(" test");
    }

    @Test
    void classifiesTestFailuresFromSurefireXml() throws Exception {
        Path results = worktree.resolve("target/surefire-reports");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-com.example.CalculatorTest.xml"), """
                <testsuite name="com.example.CalculatorTest">
                  <testcase name="fails" classname="com.example.CalculatorTest">
                    <failure message="expected 2 but was 3"/>
                  </testcase>
                </testsuite>
                """);
        CommandRunner runner = (directory, command, timeout, maxBytes) ->
                result(command, 1, "test failure", false);
        MavenProjectAdapter adapter = new MavenProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.TEST_FAILURES);
        assertThat(result.testsRun()).isEqualTo(1);
        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.failedTests()).hasSize(1);
        assertThat(result.failedTests().get(0).className()).isEqualTo("com.example.CalculatorTest");
    }

    @Test
    void classifiesTestFailuresFromOutputWhenXmlIsMissing() {
        CommandRunner runner = (directory, command, timeout, maxBytes) ->
                result(command, 1, "Tests run: 3, Failures: 1, Errors: 0, Skipped: 0", false);
        MavenProjectAdapter adapter = new MavenProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.TEST_FAILURES);
    }

    @Test
    void discoversReportsFromMavenModules() throws Exception {
        Path results = worktree.resolve("service/target/surefire-reports");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-com.example.ServiceTest.xml"), """
                <testsuite name="com.example.ServiceTest">
                  <testcase name="fails" classname="com.example.ServiceTest">
                    <failure message="module failure"/>
                  </testcase>
                </testsuite>
                """);
        CommandRunner runner = (directory, command, timeout, maxBytes) ->
                result(command, 1, "", false);
        MavenProjectAdapter adapter = new MavenProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.TEST_FAILURES);
        assertThat(result.testsRun()).isEqualTo(1);
        assertThat(result.failures()).isEqualTo(1);
    }

    @Test
    void classifiesCompilationFailureDistinctly() {
        CommandRunner runner = (directory, command, timeout, maxBytes) ->
                result(command, 1, "[ERROR] COMPILATION ERROR: cannot find symbol", false);
        MavenProjectAdapter adapter = new MavenProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.COMPILE_FAILURE);
    }

    @Test
    void classifiesTimeouts() {
        CommandRunner runner = (directory, command, timeout, maxBytes) ->
                new ProcessResult(
                        command,
                        -1,
                        "",
                        "",
                        true,
                        false,
                        false,
                        Duration.ofSeconds(1));
        MavenProjectAdapter adapter = new MavenProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("timed out"));
    }

    @Test
    void classifiesStartupErrors() {
        CommandRunner runner = (directory, command, timeout, maxBytes) -> {
            throw new ProcessExecutionException("mvn not found");
        };
        MavenProjectAdapter adapter = new MavenProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.outputTail()).contains("mvn not found");
    }

    @Test
    void usesTheMavenWrapperWhenPresent() throws Exception {
        Path wrapper = worktree.resolve(wrapperName());
        Files.writeString(wrapper, "");
        if (!isWindows()) {
            assertThat(wrapper.toFile().setExecutable(true)).isTrue();
        }
        MavenProjectAdapter adapter = new MavenProjectAdapter(
                (directory, command, timeout, maxBytes) -> result(command, 0, "", false),
                new TestResultXmlParser());

        List<String> command = adapter.testCommand(worktree);

        assertThat(String.join(" ", command)).contains("mvnw");
    }

    private static ProcessResult result(List<String> command, int exitCode, String stdout, boolean timedOut) {
        return new ProcessResult(
                command,
                exitCode,
                stdout,
                "",
                timedOut,
                false,
                false,
                Duration.ofMillis(10));
    }

    private static String wrapperName() {
        return isWindows() ? "mvnw.cmd" : "mvnw";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
