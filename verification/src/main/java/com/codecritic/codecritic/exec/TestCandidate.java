package com.codecritic.exec;

public record TestCandidate(
        String source,
        String relativePath,
        String className
) {
    public TestCandidate {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Test source must not be blank");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Test path must not be blank");
        }
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Test class name must not be blank");
        }
    }
}
