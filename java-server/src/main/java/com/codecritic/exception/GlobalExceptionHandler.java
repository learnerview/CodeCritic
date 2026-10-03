package com.codecritic.exception;

import com.codecritic.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiErrorResponse.of("UNAUTHORIZED", ex.getMessage(), null)
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.of("BAD_REQUEST", "Malformed request body", null)
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.of("VALIDATION_ERROR", message, null)
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.of("BAD_REQUEST", ex.getMessage(), null)
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        log.info("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse.of("NOT_FOUND", ex.getMessage(), null)
        );
    }

    /**
     * Unmapped static-resource paths arrive as {@link NoResourceFoundException} and were
     * being swallowed by the catch-all {@code Exception} handler below, so any request to
     * a path with no handler -- {@code /ready}, a typo'd endpoint, a stale bookmark --
     * returned <em>500 Internal Server Error</em> and logged a full stack trace. Callers
     * reasonably escalated monitoring on it, and a probe expecting 404 got 500 instead.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        // Spring reports the resource path without its leading slash ("ready", not
        // "/ready"), so normalise it before echoing it back to the caller.
        String path = ex.getResourcePath();
        if (path != null && !path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        log.info("No handler for {} {}", ex.getHttpMethod(), path);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse.of("NOT_FOUND",
                        "No endpoint " + ex.getHttpMethod() + " " + path, null)
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        log.error("Internal server error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", null)
        );
    }
}