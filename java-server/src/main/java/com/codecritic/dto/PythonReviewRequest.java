package com.codecritic.dto;

import jakarta.validation.constraints.NotBlank;

public record PythonReviewRequest(@NotBlank String code) {
}
