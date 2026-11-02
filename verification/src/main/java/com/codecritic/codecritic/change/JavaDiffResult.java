package com.codecritic.change;

import java.util.List;

public record JavaDiffResult(
        List<MethodChange> changes,
        List<String> warnings
) {
    public JavaDiffResult {
        changes = List.copyOf(changes);
        warnings = List.copyOf(warnings);
    }
}
