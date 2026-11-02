package com.codecritic.report;

public record Evidence(
        String id,
        String type,
        String path,
        int line,
        String description
) {
}
