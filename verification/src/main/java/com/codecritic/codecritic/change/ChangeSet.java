package com.codecritic.change;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ChangeSet(
        Path repository,
        String baseRef,
        String headRef,
        List<ChangedFile> files,
        Map<String, String> headSources
) {
    public ChangeSet {
        files = List.copyOf(files);
        headSources = Map.copyOf(headSources);
    }
}
