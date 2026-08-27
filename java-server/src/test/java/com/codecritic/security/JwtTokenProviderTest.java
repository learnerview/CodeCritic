package com.codecritic.security;

import com.codecritic.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-that-is-at-least-32-bytes-long-1234");
        properties.setExpirationMs(60_000L);
        properties.setIssuer("codecritic");
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[0]);
        tokenProvider = new JwtTokenProvider(properties, env);
    }

    @Test
    void generateToken_returnsValidToken() {
        String token = tokenProvider.generateToken("admin");
        assertNotNull(token);
        assertTrue(tokenProvider.validateToken(token));
    }

    @Test
    void generateToken_includesCustomClaims() {
        String token = tokenProvider.generateToken("admin", Map.of("role", "ROLE_USER"));
        assertEquals("admin", tokenProvider.getUsername(token));
        assertEquals("ROLE_USER", tokenProvider.parseClaims(token).get("role"));
    }

    @Test
    void validateToken_rejectsTamperedToken() {
        String token = tokenProvider.generateToken("admin");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertFalse(tokenProvider.validateToken(tampered));
    }

    @Test
    void validateToken_rejectsGarbage() {
        assertFalse(tokenProvider.validateToken("not-a-jwt"));
        assertFalse(tokenProvider.validateToken(""));
    }
}
