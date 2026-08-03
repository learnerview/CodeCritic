package com.codecritic.service.impl;

import com.codecritic.config.JwtProperties;
import com.codecritic.dto.auth.LoginRequest;
import com.codecritic.dto.auth.LoginResponse;
import com.codecritic.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceImplTest {

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-that-is-at-least-32-bytes-long-1234");
        properties.setExpirationMs(60_000L);
        properties.setIssuer("codecritic");
        authService = new AuthServiceImpl(new JwtTokenProvider(properties), properties);
        ReflectionTestUtils.setField(authService, "configuredUsername", "admin");
        ReflectionTestUtils.setField(authService, "configuredPassword", "admin");
    }

    @Test
    void authenticate_validCredentials_returnsToken() {
        LoginResponse response = authService.authenticate(
                LoginRequest.builder().username("admin").password("admin").build());
        assertNotNull(response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("admin", response.getUsername());
        assertTrue(response.getExpiresInMs() > 0);
    }

    @Test
    void authenticate_wrongPassword_throws() {
        assertThrows(BadCredentialsException.class, () -> authService.authenticate(
                LoginRequest.builder().username("admin").password("wrong").build()));
    }

    @Test
    void authenticate_unknownUser_throws() {
        assertThrows(BadCredentialsException.class, () -> authService.authenticate(
                LoginRequest.builder().username("nobody").password("admin").build()));
    }
}
