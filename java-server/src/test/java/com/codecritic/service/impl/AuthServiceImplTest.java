package com.codecritic.service.impl;

import com.codecritic.config.JwtProperties;
import com.codecritic.dto.auth.LoginRequest;
import com.codecritic.dto.auth.LoginResponse;
import com.codecritic.dto.auth.RegisterRequest;
import com.codecritic.model.User;
import com.codecritic.repository.UserRepository;
import com.codecritic.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceImplTest {

    private AuthServiceImpl authService;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-that-is-at-least-32-bytes-long-1234");
        properties.setExpirationMs(60_000L);
        properties.setIssuer("codecritic");
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthServiceImpl(new JwtTokenProvider(properties), properties, userRepository, passwordEncoder);
    }

    @Test
    void authenticate_validCredentials_returnsToken() {
        User user = User.builder()
                .username("admin")
                .password(passwordEncoder.encode("admin"))
                .role("ROLE_USER")
                .build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        LoginResponse response = authService.authenticate(
                LoginRequest.builder().username("admin").password("admin").build());
        assertNotNull(response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("admin", response.getUsername());
        assertTrue(response.getExpiresInMs() > 0);
    }

    @Test
    void authenticate_wrongPassword_throws() {
        User user = User.builder()
                .username("admin")
                .password(passwordEncoder.encode("admin"))
                .role("ROLE_USER")
                .build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThrows(BadCredentialsException.class, () -> authService.authenticate(
                LoginRequest.builder().username("admin").password("wrong").build()));
    }

    @Test
    void authenticate_unknownUser_throws() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> authService.authenticate(
                LoginRequest.builder().username("nobody").password("admin").build()));
    }

    @Test
    void register_newUser_returnsToken() {
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LoginResponse response = authService.register(
                RegisterRequest.builder().username("newuser").password("newpass").build());
        assertNotNull(response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("newuser", response.getUsername());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_existingUser_throws() {
        User existingUser = User.builder()
                .username("existing")
                .password(passwordEncoder.encode("pass"))
                .role("ROLE_USER")
                .build();
        when(userRepository.findByUsername("existing")).thenReturn(Optional.of(existingUser));

        assertThrows(BadCredentialsException.class, () -> authService.register(
                RegisterRequest.builder().username("existing").password("pass").build()));
    }
}