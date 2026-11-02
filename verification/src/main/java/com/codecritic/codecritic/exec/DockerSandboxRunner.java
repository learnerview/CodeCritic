package com.codecritic.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class DockerSandboxRunner implements CommandRunner {

    private static final String DEFAULT_MAVEN_IMAGE = "maven:3.9-eclipse-temurin-21";
    private static final String DEFAULT_GRADLE_IMAGE = "gradle:8.10.2-jdk21";
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(30);

    private final CommandRunner hostRunner;
    private final String mavenImage;
    private final String gradleImage;
    private final String unixUser;

    public DockerSandboxRunner() {
        this(
                new ProcessRunner(),
                image("CODECRITIC_MAVEN_IMAGE", DEFAULT_MAVEN_IMAGE),
                image("CODECRITIC_GRADLE_IMAGE", DEFAULT_GRADLE_IMAGE),
                unixUser());
    }

    DockerSandboxRunner(
            CommandRunner hostRunner,
            String mavenImage,
            String gradleImage,
            String unixUser) {
        this.hostRunner = hostRunner;
        this.mavenImage = mavenImage;
        this.gradleImage = gradleImage;
        this.unixUser = unixUser;
    }

    @Override
    public ProcessResult run(
            Path workingDirectory,
            List<String> command,
            Duration timeout,
            int maxOutputBytes) {
        SandboxedCommand sandboxed = sandboxedCommand(workingDirectory, command);
        String containerName = "codecritic-" + UUID.randomUUID();
        List<String> dockerCommand = dockerCommand(
                workingDirectory,
                sandboxed,
                containerName);

        ProcessResult result;
        try {
            result = hostRunner.run(
                    workingDirectory,
                    dockerCommand,
                    timeout,
                    maxOutputBytes);
        } catch (RuntimeException e) {
            cleanup(containerName);
            throw e;
        }

        if (result.timedOut() || isDockerInfrastructureFailure(result)) {
            cleanup(containerName);
        }
        if (!result.timedOut() && isDockerInfrastructureFailure(result)) {
            throw new ProcessExecutionException(
                    "Docker sandbox failed: " + dockerError(result));
        }

        return new ProcessResult(
                command,
                result.exitCode(),
                result.stdout(),
                result.stderr(),
                result.timedOut(),
                result.stdoutTruncated(),
                result.stderrTruncated(),
                result.duration());
    }

    @Override
    public String environment() {
        return "docker";
    }

    private List<String> dockerCommand(
            Path workingDirectory,
            SandboxedCommand sandboxed,
            String containerName) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("--init");
        command.add("--name");
        command.add(containerName);
        command.add("--label");
        command.add("codecritic.sandbox=true");
        command.add("--network");
        command.add("bridge");
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--security-opt");
        command.add("no-new-privileges");
        command.add("--pids-limit");
        command.add("512");
        command.add("--memory");
        command.add("2g");
        command.add("--cpus");
        command.add("2");
        command.add("--read-only");
        command.add("--tmpfs");
        command.add("/tmp:rw,exec,nosuid,size=1g");
        command.add("--env");
        command.add("HOME=/tmp");
        command.add("--env");
        command.add("MAVEN_CONFIG=/tmp/.m2");
        command.add("--env");
        command.add("MAVEN_OPTS=-Dmaven.repo.local=/tmp/.m2/repository");
        command.add("--env");
        command.add("GRADLE_USER_HOME=/tmp/.gradle");
        command.add("--env");
        command.add("CI=true");
        command.add("--env");
        command.add("GIT_TERMINAL_PROMPT=0");
        if (unixUser != null) {
            command.add("--user");
            command.add(unixUser);
        }
        command.add("--mount");
        command.add("type=bind,source="
                + workingDirectory.toAbsolutePath().normalize()
                + ",target=/workspace");
        command.add("--workdir");
        command.add("/workspace");
        command.add(sandboxed.image());
        command.add("sh");
        command.add("-c");
        command.add(shellJoin(sandboxed.command()));
        return List.copyOf(command);
    }

    private SandboxedCommand sandboxedCommand(Path worktree, List<String> command) {
        List<String> unwrapped = unwrapCmd(command);
        if (unwrapped.isEmpty()) {
            throw new IllegalArgumentException("Test command must not be empty");
        }

        String executable = unwrapped.get(0);
        List<String> arguments = unwrapped.subList(1, unwrapped.size());
        String fileName = fileName(executable).toLowerCase(Locale.ROOT);
        boolean insideWorktree = isInside(worktree, executable);
        Path worktreePath = worktree.toAbsolutePath().normalize();

        if (fileName.equals("mvnw") || fileName.equals("mvnw.cmd")) {
            if (Files.isRegularFile(worktreePath.resolve("mvnw"))) {
                return new SandboxedCommand(
                        prepend(List.of("sh", "./mvnw"), arguments),
                        mavenImage);
            }
            return new SandboxedCommand(
                    prepend(List.of("mvn"), arguments),
                    mavenImage);
        }
        if (fileName.equals("gradlew") || fileName.equals("gradlew.bat")) {
            if (Files.isRegularFile(worktreePath.resolve("gradlew"))) {
                return new SandboxedCommand(
                        prepend(List.of("sh", "./gradlew"), arguments),
                        gradleImage);
            }
            return new SandboxedCommand(
                    prepend(List.of("gradle"), arguments),
                    gradleImage);
        }
        if (fileName.equals("mvn") || fileName.equals("mvn.cmd")) {
            return new SandboxedCommand(
                    prepend(List.of("mvn"), arguments),
                    mavenImage);
        }
        if (fileName.equals("gradle") || fileName.equals("gradle.bat")) {
            return new SandboxedCommand(
                    prepend(List.of("gradle"), arguments),
                    gradleImage);
        }
        if (insideWorktree) {
            throw new IllegalArgumentException(
                    "Unsupported build executable inside the worktree: " + executable);
        }
        throw new IllegalArgumentException("Unsupported build executable: " + executable);
    }

    private static List<String> unwrapCmd(List<String> command) {
        if (command.size() >= 4
                && "cmd.exe".equalsIgnoreCase(command.get(0))
                && "/d".equalsIgnoreCase(command.get(1))
                && "/s".equalsIgnoreCase(command.get(2))
                && "/c".equalsIgnoreCase(command.get(3))) {
            return command.subList(4, command.size());
        }
        return command;
    }

    private static boolean isInside(Path worktree, String executable) {
        try {
            Path path = Path.of(executable).toAbsolutePath().normalize();
            return path.startsWith(worktree.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String fileName(String executable) {
        try {
            Path name = Path.of(executable).getFileName();
            return name == null ? executable : name.toString();
        } catch (RuntimeException e) {
            return executable;
        }
    }

    private void cleanup(String containerName) {
        try {
            hostRunner.run(
                    Path.of(".").toAbsolutePath(),
                    List.of("docker", "rm", "-f", containerName),
                    CLEANUP_TIMEOUT,
                    20_000);
        } catch (RuntimeException ignored) {
            // The container may already be gone.
        }
    }

    private static List<String> prepend(List<String> prefix, List<String> arguments) {
        List<String> command = new ArrayList<>(prefix.size() + arguments.size());
        command.addAll(prefix);
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static String shellJoin(List<String> command) {
        List<String> quoted = new ArrayList<>(command.size());
        for (String part : command) {
            quoted.add("'" + part.replace("'", "'\"'\"'") + "'");
        }
        return String.join(" ", quoted);
    }

    private static String dockerError(ProcessResult result) {
        String output = (result.stderr() + "\n" + result.stdout()).strip();
        if (output.length() > 2_000) {
            output = output.substring(0, 2_000) + "...";
        }
        return output.isBlank() ? "docker exited with code 125" : output;
    }

    private static boolean isDockerInfrastructureFailure(ProcessResult result) {
        if (result.exitCode() == 125) {
            return true;
        }
        String output = (result.stderr() + "\n" + result.stdout()).toLowerCase(Locale.ROOT);
        return output.contains("failed to connect to the docker api")
                || output.contains("cannot connect to the docker daemon")
                || output.contains("is the docker daemon running")
                || output.contains("docker daemon is not running");
    }

    private static String image(String environmentVariable, String fallback) {
        String value = System.getenv(environmentVariable);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String unixUser() {
        if (isWindows()) {
            return null;
        }
        String uid = runId("-u");
        String gid = runId("-g");
        if (uid == null || gid == null) {
            return null;
        }
        return uid + ":" + gid;
    }

    private static String runId(String flag) {
        try {
            Process process = new ProcessBuilder("id", flag)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            return output.matches("\\d+") ? output : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private record SandboxedCommand(List<String> command, String image) {
    }
}
