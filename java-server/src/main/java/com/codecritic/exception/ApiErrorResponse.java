package com.codecritic.exception;

public record ApiErrorResponse(String status, String error, String path) {

    public static ApiErrorResponse of(String status, String error, String path) {
        return new ApiErrorResponse(status, error, path);
    }
}