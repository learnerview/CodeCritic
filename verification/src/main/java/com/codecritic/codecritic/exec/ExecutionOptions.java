package com.codecritic.exec;

import java.time.Duration;

public record ExecutionOptions(
        Duration timeout,
        int maxOutputBytes
) {
    public ExecutionOptions {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Test timeout must be positive");
        }
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("Output limit must be positive");
        }
    }

    public static ExecutionOptions defaults() {
        return new ExecutionOptions(Duration.ofMinutes(10), 200_000);
    }
}
