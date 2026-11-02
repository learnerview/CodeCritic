package com.codecritic.exec;

public record TestFailure(
        String className,
        String testName,
        String message
) {
}
