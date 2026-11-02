package com.codecritic.exec;

import java.time.Duration;
import java.util.List;

public record ProcessResult(
        List<String> command,
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        Duration duration
) {
    public ProcessResult {
        command = List.copyOf(command);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
