package com.codecritic.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public runtime configuration for the browser. The Python agent is deliberately
 * not exposed here; Java is the only service-to-service boundary.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Value("${simplydone4j.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    @GetMapping
    public Map<String, Object> config() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "");
        return Map.of(
                "schedulerEnabled", schedulerEnabled,
                "redisConfigured", !redisUrl.isBlank()
        );
    }
}
