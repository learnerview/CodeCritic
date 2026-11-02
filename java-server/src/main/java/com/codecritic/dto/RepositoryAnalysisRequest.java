package com.codecritic.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RepositoryAnalysisRequest(
        @NotBlank String repoUrl,
        String branch,
        @Min(1) @Max(30) Integer maxFiles,
        String githubToken) {
}
