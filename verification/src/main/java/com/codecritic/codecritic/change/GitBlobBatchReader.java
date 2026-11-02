package com.codecritic.change;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class GitBlobBatchReader {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    Map<String, String> read(Path repository, String ref, List<String> paths) {
        if (paths.isEmpty()) {
            return Map.of();
        }

        ProcessBuilder builder = new ProcessBuilder(
                "git",
                "-C",
                repository.toAbsolutePath().normalize().toString(),
                "cat-file",
                "--batch");
        builder.redirectErrorStream(false);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process process = null;
        try {
            process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                for (String path : paths) {
                    stdin.write((ref + ":" + path + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }

            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Process running = process;
            Thread errorDrain = Thread.startVirtualThread(() -> {
                try (InputStream error = running.getErrorStream()) {
                    error.transferTo(stderr);
                } catch (IOException ignored) {
                }
            });

            Map<String, String> sources = new LinkedHashMap<>();
            try (BufferedInputStream stdout = new BufferedInputStream(process.getInputStream())) {
                for (String path : paths) {
                    String header = readLine(stdout);
                    if (header == null) {
                        throw new GitException("git cat-file ended before returning " + path);
                    }
                    if (header.endsWith(" missing") || header.endsWith(" ambiguous")) {
                        continue;
                    }

                    String[] parts = header.split(" ");
                    if (parts.length != 3) {
                        throw new GitException("Unexpected git cat-file header: " + header);
                    }

                    int size = Integer.parseInt(parts[2]);
                    byte[] content = stdout.readNBytes(size);
                    if (content.length != size) {
                        throw new GitException("Short read from git cat-file for " + path);
                    }
                    if (stdout.read() != '\n') {
                        throw new GitException("Malformed git cat-file response for " + path);
                    }
                    sources.put(path, new String(content, StandardCharsets.UTF_8));
                }
            }

            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitException("git cat-file timed out");
            }
            errorDrain.join(1_000);

            if (process.exitValue() != 0) {
                throw new GitException("git cat-file failed: "
                        + stderr.toString(StandardCharsets.UTF_8).strip());
            }
            return sources;
        } catch (IOException e) {
            throw new GitException("Unable to read git blobs", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("Interrupted while reading git blobs", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') {
                return line.toString(StandardCharsets.UTF_8);
            }
            line.write(next);
        }
        if (line.size() == 0) {
            return null;
        }
        return line.toString(StandardCharsets.UTF_8);
    }
}
