package com.codecritic.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GradleProjectAdapterTest {

    @TempDir
    Path worktree;

    @Test
    void runsGradleTestAndUsesPlainNoDaemonOutput() {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        CommandRunner runner = (directory, command, timeout, maxBytes) -> {
            captured.set(List.copyOf(command));
            return new ProcessResult(
                    command,
                    0,
                    "",
                    "",
                    false,
                    false,
                    false,
                    Duration.ofMillis(10));
        };
        GradleProjectAdapter adapter = new GradleProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.adapter()).isEqualTo("gradle");
        assertThat(captured.get()).contains("--no-daemon", "--console=plain", "test");
    }

    @Test
    void runsOnlyTheSelectedTest() {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        CommandRunner runner = (directory, command, timeout, maxBytes) -> {
            captured.set(List.copyOf(command));
            return new ProcessResult(
                    command,
                    0,
                    "",
                    "",
                    false,
                    false,
                    false,
                    Duration.ofMillis(10));
        };
        GradleProjectAdapter adapter = new GradleProjectAdapter(runner, new TestResultXmlParser());

        adapter.execute(
                worktree,
                "HEAD",
                ExecutionOptions.defaults(),
                "com.example.CalculatorTest");

        assertThat(captured.get()).containsSequence(
                "test",
                "--tests",
                "com.example.CalculatorTest");
    }

    @Test
    void discoversReportsFromGradleSubprojects() throws Exception {
        Path results = worktree.resolve("lib/build/test-results/test");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-com.example.LibTest.xml"), """
                <testsuite name="com.example.LibTest">
                  <testcase name="fails" classname="com.example.LibTest">
                    <failure message="subproject failure"/>
                  </testcase>
                </testsuite>
                """);
        CommandRunner runner = (directory, command, timeout, maxBytes) ->
                new ProcessResult(
                        command,
                        1,
                        "",
                        "",
                        false,
                        false,
                        false,
                        Duration.ofMillis(10));
        GradleProjectAdapter adapter = new GradleProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.TEST_FAILURES);
        assertThat(result.testsRun()).isEqualTo(1);
        assertThat(result.failures()).isEqualTo(1);
    }

    @Test
    void parsesGradleTestResults() throws Exception {
        Path results = worktree.resolve("build/test-results/test");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-com.example.GradleTest.xml"), """
                <testsuite name="com.example.GradleTest">
                  <testcase name="fails" classname="com.example.GradleTest">
                    <failure message="boom"/>
                  </testcase>
                </testsuite>
                """);
        CommandRunner runner = (directory, command, timeout, maxBytes) ->
                new ProcessResult(
                        command,
                        1,
                        "FAILURE: Build failed with an exception.",
                        "",
                        false,
                        false,
                        false,
                        Duration.ofMillis(10));
        GradleProjectAdapter adapter = new GradleProjectAdapter(runner, new TestResultXmlParser());

        TestSuiteResult result = adapter.execute(worktree, "HEAD", ExecutionOptions.defaults());

        assertThat(result.status()).isEqualTo(ExecutionStatus.TEST_FAILURES);
        assertThat(result.failures()).isEqualTo(1);
    }
}
