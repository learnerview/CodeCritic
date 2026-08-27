package com.codecritic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "codecritic.jwt")
public class JwtProperties {

    private String secret;
    private long expirationMs = 86400000L;
    private String issuer = "codecritic";
    private String[] permitAllPaths = {
            "/error",
            "/health",
            "/ready",
            "/api/auth/login",
            "/api/auth/register",
            "/api/config"
    };
}
