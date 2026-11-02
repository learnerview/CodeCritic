package com.codecritic.change;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class GitCommandRunner {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    public Result run(Path repository, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toAbsolutePath().normalize().toString());
        command.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");

        try {
            Process process = builder.start();
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));

            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GitException("git command timed out: " + String.join(" ", command));
            }

            return new Result(process.exitValue(), stdout.join(), stderr.join());
        } catch (IOException e) {
            throw new GitException("Unable to run git. Is git installed and on PATH?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("Interrupted while running git", e);
        }
    }

    public String runText(Path repository, String... arguments) {
        Result result = run(repository, arguments);
        if (result.exitCode() != 0) {
            String stderr = new String(result.stderr(), StandardCharsets.UTF_8).strip();
            throw new GitException("git " + String.join(" ", arguments) + " failed: " + stderr);
        }
        return new String(result.stdout(), StandardCharsets.UTF_8);
    }

    public byte[] runBytes(Path repository, String... arguments) {
        Result result = run(repository, arguments);
        if (result.exitCode() != 0) {
            String stderr = new String(result.stderr(), StandardCharsets.UTF_8).strip();
            throw new GitException("git " + String.join(" ", arguments) + " failed: " + stderr);
        }
        return result.stdout();
    }

    private static byte[] readAll(InputStream stream) {
        try (InputStream input = stream) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new GitException("Unable to read git output", e);
        }
    }

    public record Result(int exitCode, byte[] stdout, byte[] stderr) {
    }
}
