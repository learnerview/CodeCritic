package com.codecritic.change;

public record ChangedFile(
        String oldPath,
        String newPath,
        ChangeKind kind,
        String baseSource,
        String headSource
) {
    public String displayPath() {
        return newPath != null ? newPath : oldPath;
    }
}
