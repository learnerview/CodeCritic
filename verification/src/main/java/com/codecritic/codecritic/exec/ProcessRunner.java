package com.codecritic.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class ProcessRunner implements CommandRunner {

    private static final Duration STREAM_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    @Override
    public ProcessResult run(
            Path workingDirectory,
            List<String> command,
            Duration timeout,
            int maxOutputBytes) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Command must not be empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("Output limit must be positive");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(false);
        builder.environment().put("CI", "true");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");

        long started = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessExecutionException(
                    "Unable to start " + command.get(0) + ": " + e.getMessage(), e);
        }

        CompletableFuture<Captured> stdout = CompletableFuture.supplyAsync(
                () -> readCapped(process.getInputStream(), maxOutputBytes));
        CompletableFuture<Captured> stderr = CompletableFuture.supplyAsync(
                () -> readCapped(process.getErrorStream(), maxOutputBytes));

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            throw new ProcessExecutionException("Interrupted while waiting for " + command.get(0), e);
        }

        if (!finished) {
            destroyTree(process);
            waitQuietly(process, STREAM_DRAIN_TIMEOUT);
        }

        Captured out = join(stdout);
        Captured err = join(stderr);
        int exitCode = finished ? process.exitValue() : -1;
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        return new ProcessResult(
                command,
                exitCode,
                new String(out.bytes(), StandardCharsets.UTF_8),
                new String(err.bytes(), StandardCharsets.UTF_8),
                !finished,
                out.truncated(),
                err.truncated(),
                elapsed);
    }

    private static Captured readCapped(InputStream stream, int maxBytes) {
        try (InputStream input = stream) {
            ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            boolean truncated = false;
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = maxBytes - captured.size();
                if (remaining > 0) {
                    int toWrite = Math.min(read, remaining);
                    captured.write(buffer, 0, toWrite);
                    if (toWrite < read) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            }
            return new Captured(captured.toByteArray(), truncated);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static Captured join(CompletableFuture<Captured> future) {
        try {
            return future.get(STREAM_DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Captured(new byte[0], false);
        } catch (Exception e) {
            return new Captured(new byte[0], false);
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup; the parent is destroyed below.
            }
        });
        process.destroyForcibly();
    }

    private static void waitQuietly(Process process, Duration timeout) {
        try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Captured(byte[] bytes, boolean truncated) {
    }
}
