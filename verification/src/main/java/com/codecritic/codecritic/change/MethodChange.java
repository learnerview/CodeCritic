package com.codecritic.change;

import java.util.List;

public record MethodChange(
        String path,
        String key,
        String className,
        String methodName,
        MethodChangeKind kind,
        String baseSignature,
        String headSignature,
        boolean publicApi,
        int baseComplexity,
        int headComplexity,
        int baseLine,
        int headLine,
        List<String> details
) {
    public MethodChange {
        details = List.copyOf(details);
    }

    public int complexityDelta() {
        return headComplexity - baseComplexity;
    }

    public String displayName() {
        return className + "#" + methodName;
    }
}
