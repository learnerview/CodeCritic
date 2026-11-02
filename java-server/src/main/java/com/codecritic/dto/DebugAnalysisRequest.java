package com.codecritic.dto;

import jakarta.validation.constraints.NotBlank;

public record DebugAnalysisRequest(
        @NotBlank String code,
        @NotBlank String errorLog,
        String language) {
}
