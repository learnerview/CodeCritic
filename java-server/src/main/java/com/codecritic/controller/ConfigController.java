package com.codecritic.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public runtime configuration for the browser. On Render the Python agent
 * lives at a different URL than localhost, so the dashboard asks the Java
 * service where the agent is instead of hardcoding it.
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
                "pythonAgentUrl", System.getenv().getOrDefault("PYTHON_AGENT_URL", "http://localhost:8000"),
                "javaServerUrl", System.getenv().getOrDefault("JAVA_SERVER_URL", ""),
                "schedulerEnabled", schedulerEnabled,
                "redisConfigured", !redisUrl.isBlank()
        );
    }
}
