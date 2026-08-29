package com.codecritic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;
import java.time.Duration;

/**
 * Configures Redis from REDIS_URL (e.g. Upstash on Render) or falls back to
 * local development settings.
 *
 * Supported URL formats:
 *   redis://localhost:6379
 *   redis://user:password@host:port
 *   rediss://user:password@host:port   (TLS, used by Upstash / Render KV)
 */
@Configuration
public class RedisConfig {

    /**
     * Increase the Lettuce command timeout slightly to tolerate brief Redis hiccups.
     * This is a short-term mitigation; long-term fixes should address Redis stability.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceCustomizer() {
        return builder -> builder.commandTimeout(Duration.ofSeconds(5));
    }

    @Value("${spring.redis.url:}")
    private String redisUrlProperty;

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        // Prefer the Spring property (which can be bound to the REDIS_URL env in application.yml),
        // then fall back to the raw env var, then to a local default.
        String redisUrl = (redisUrlProperty != null && !redisUrlProperty.isBlank())
                ? redisUrlProperty
                : System.getenv("REDIS_URL");

        if (redisUrl == null || redisUrl.isBlank()) {
            redisUrl = "redis://localhost:6379";
        }

        boolean useSsl = redisUrl.startsWith("rediss://");

        String normalized = useSsl ? redisUrl.replaceFirst("rediss://", "redis://") : redisUrl;
        URI uri = URI.create(normalized);

        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(uri.getHost());
        serverConfig.setPort(uri.getPort() > 0 ? uri.getPort() : 6379);

        if (uri.getUserInfo() != null) {
            String[] userInfo = uri.getUserInfo().split(":", 2);
            if (userInfo.length == 2) {
                serverConfig.setUsername(userInfo[0]);
                serverConfig.setPassword(userInfo[1]);
            } else if (userInfo.length == 1) {
                serverConfig.setPassword(userInfo[0]);
            }
        }

        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfigBuilder =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(2));

        if (useSsl) {
            clientConfigBuilder.useSsl();
        }

        return new LettuceConnectionFactory(serverConfig, clientConfigBuilder.build());
    }
}
