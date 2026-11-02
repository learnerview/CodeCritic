package com.codecritic.change;

public record MethodInfo(
        String key,
        String className,
        String methodName,
        String signature,
        String body,
        int line,
        boolean publicApi,
        int cyclomaticComplexity
) {
}
