package com.codecritic.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Simple health endpoint for local smoke tests and Docker validation.
 *
 * Why this exists:
 * - The Java service is the core analysis backend, so a minimal health route gives a
 *   stable signal that the container started successfully.
 * - Keeping it here avoids needing the Actuator dependency for a single health check.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "java-server"));
    }
}
