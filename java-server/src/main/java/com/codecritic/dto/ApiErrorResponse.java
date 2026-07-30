package com.codecritic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(String status, String message, Instant timestamp) {

    public static ApiErrorResponse of(String status, String message, Instant timestamp) {
        return new ApiErrorResponse(status, message, timestamp != null ? timestamp : Instant.now());
    }
}