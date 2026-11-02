package com.codecritic.exec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerSandboxRunnerTest {

    @TempDir
    Path worktree;

    @Test
    void wrapsMavenCommandInABoundedDockerContainer() {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        CommandRunner host = (directory, command, timeout, maxBytes) -> {
            captured.set(List.copyOf(command));
            return result(command, 0, "", false);
        };
        DockerSandboxRunner runner = new DockerSandboxRunner(
                host,
                "maven:test",
                "gradle:test",
                null);

        runner.run(worktree, List.of("mvn", "-B", "test"), Duration.ofSeconds(10), 1_000);

        List<String> docker = captured.get();
        assertThat(docker).startsWith("docker", "run");
        assertThat(docker).contains(
                "--rm",
                "--init",
                "--read-only",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--pids-limit",
                "512",
                "--memory",
                "2g",
                "--cpus",
                "2",
                "maven:test",
                "sh",
                "-c");
        assertThat(String.join(" ", docker))
                .contains("type=bind,source=" + worktree.toAbsolutePath().normalize())
                .contains("target=/workspace")
                .contains("'mvn' '-B' 'test'");
        assertThat(runner.environment()).isEqualTo("docker");
    }

    @Test
    void mapsTheWindowsWrapperToThePosixScriptInsideTheContainer() throws Exception {
        Files.writeString(worktree.resolve("mvnw"), "");
        AtomicReference<List<String>> captured = new AtomicReference<>();
        CommandRunner host = (directory, command, timeout, maxBytes) -> {
            captured.set(List.copyOf(command));
            return result(command, 0, "", false);
        };
        DockerSandboxRunner runner = new DockerSandboxRunner(
                host,
                "maven:test",
                "gradle:test",
                null);
        List<String> command = List.of(
                "cmd.exe",
                "/d",
                "/s",
                "/c",
                worktree.resolve("mvnw.cmd").toString(),
                "-B",
                "test");

        runner.run(worktree, command, Duration.ofSeconds(10), 1_000);

        assertThat(String.join(" ", captured.get()))
                .contains("'sh' './mvnw' '-B' 'test'");
    }

    @Test
    void selectsTheGradleImage() {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        CommandRunner host = (directory, command, timeout, maxBytes) -> {
            captured.set(List.copyOf(command));
            return result(command, 0, "", false);
        };
        DockerSandboxRunner runner = new DockerSandboxRunner(
                host,
                "maven:test",
                "gradle:test",
                null);

        runner.run(worktree, List.of("gradle", "test"), Duration.ofSeconds(10), 1_000);

        assertThat(captured.get()).contains("gradle:test");
        assertThat(String.join(" ", captured.get())).contains("'gradle' 'test'");
    }

    @Test
    void forceRemovesTheContainerOnTimeout() {
        AtomicReference<String> containerName = new AtomicReference<>();
        AtomicReference<List<String>> cleanup = new AtomicReference<>();
        CommandRunner host = (directory, command, timeout, maxBytes) -> {
            if (command.contains("run")) {
                containerName.set(command.get(command.indexOf("--name") + 1));
                return result(command, -1, "", true);
            }
            cleanup.set(List.copyOf(command));
            return result(command, 0, "", false);
        };
        DockerSandboxRunner runner = new DockerSandboxRunner(
                host,
                "maven:test",
                "gradle:test",
                null);

        ProcessResult result = runner.run(
                worktree,
                List.of("mvn", "test"),
                Duration.ofSeconds(1),
                1_000);

        assertThat(result.timedOut()).isTrue();
        assertThat(cleanup.get()).containsExactly(
                "docker", "rm", "-f", containerName.get());
    }

    @Test
    void treatsDockerExit125AsAnInfrastructureError() {
        AtomicReference<List<String>> cleanup = new AtomicReference<>();
        CommandRunner host = (directory, command, timeout, maxBytes) -> {
            if (command.contains("run")) {
                return result(command, 125, "Cannot connect to the Docker daemon", false);
            }
            cleanup.set(List.copyOf(command));
            return result(command, 0, "", false);
        };
        DockerSandboxRunner runner = new DockerSandboxRunner(
                host,
                "maven:test",
                "gradle:test",
                null);

        assertThatThrownBy(() -> runner.run(
                worktree,
                List.of("mvn", "test"),
                Duration.ofSeconds(10),
                1_000))
                .isInstanceOf(ProcessExecutionException.class)
                .hasMessageContaining("Cannot connect to the Docker daemon");
        assertThat(cleanup.get()).contains("rm", "-f");
    }

    @Test
    void treatsAStoppedDockerDaemonAsAnInfrastructureError() {
        AtomicReference<List<String>> cleanup = new AtomicReference<>();
        CommandRunner host = (directory, command, timeout, maxBytes) -> {
            if (command.contains("run")) {
                return result(
                        command,
                        1,
                        "failed to connect to the docker API: daemon is not running",
                        false);
            }
            cleanup.set(List.copyOf(command));
            return result(command, 0, "", false);
        };
        DockerSandboxRunner runner = new DockerSandboxRunner(
                host,
                "maven:test",
                "gradle:test",
                null);

        assertThatThrownBy(() -> runner.run(
                worktree,
                List.of("mvn", "test"),
                Duration.ofSeconds(10),
                1_000))
                .isInstanceOf(ProcessExecutionException.class)
                .hasMessageContaining("daemon is not running");
        assertThat(cleanup.get()).contains("rm", "-f");
    }

    @Test
    void rejectsUnknownExecutables() {
        DockerSandboxRunner runner = new DockerSandboxRunner(
                (directory, command, timeout, maxBytes) -> result(command, 0, "", false),
                "maven:test",
                "gradle:test",
                null);

        assertThatThrownBy(() -> runner.run(
                worktree,
                List.of("python", "test.py"),
                Duration.ofSeconds(10),
                1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported build executable");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CODECRITIC_SANDBOX_TEST", matches = "true")
    void runsMavenInsideDockerWhenTheDaemonIsAvailable() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker daemon is not available");

        ProcessResult result = new DockerSandboxRunner().run(
                worktree,
                List.of("mvn", "-version"),
                Duration.ofMinutes(2),
                100_000);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Apache Maven");
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            process.getInputStream().readAllBytes();
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static ProcessResult result(
            List<String> command,
            int exitCode,
            String stderr,
            boolean timedOut) {
        return new ProcessResult(
                command,
                exitCode,
                "",
                stderr,
                timedOut,
                false,
                false,
                Duration.ofMillis(10));
    }
}
