package com.codecritic.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRunnerTest {

    @TempDir
    Path tempDir;

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void capturesOutputAndExitCode() {
        ProcessResult result = runner.run(
                tempDir,
                probe("echo", "hello", "0"),
                Duration.ofSeconds(20),
                4096);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello");
        assertThat(result.duration()).isPositive();
    }

    @Test
    void returnsNonZeroExitCode() {
        ProcessResult result = runner.run(
                tempDir,
                probe("echo", "bad", "3"),
                Duration.ofSeconds(20),
                4096);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void timesOutAndTerminatesTheProcess() {
        ProcessResult result = runner.run(
                tempDir,
                probe("sleep", "10000"),
                Duration.ofMillis(300),
                4096);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.duration()).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void destroysChildProcessesOnTimeout() throws Exception {
        Path pidFile = tempDir.resolve("child.pid");

        ProcessResult result = runner.run(
                tempDir,
                probe("spawn", pidFile.toString()),
                Duration.ofSeconds(2),
                4096);

        assertThat(result.timedOut()).isTrue();
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        Thread.sleep(200);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void capsCapturedOutput() {
        ProcessResult result = runner.run(
                tempDir,
                probe("flood", "2000"),
                Duration.ofSeconds(20),
                512);

        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(result.stdout().length()).isLessThanOrEqualTo(512);
    }

    private static List<String> probe(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ProcessProbe.class.getName());
        command.addAll(List.of(arguments));
        return command;
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }
}
