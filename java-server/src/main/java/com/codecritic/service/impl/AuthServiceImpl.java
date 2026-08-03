package com.codecritic.service.impl;

import com.codecritic.config.JwtProperties;
import com.codecritic.dto.auth.LoginRequest;
import com.codecritic.dto.auth.LoginResponse;
import com.codecritic.security.JwtTokenProvider;
import com.codecritic.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AuthService implementation — validates demo credentials and issues JWTs.
 *
 * Credentials are configured via environment properties so secrets never
 * live in the codebase. This is a demo authentication layer; swapping in a
 * real UserDetailsService-backed flow only requires replacing this class.
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;

    @Value("${codecritic.auth.username:admin}")
    private String configuredUsername;

    @Value("${codecritic.auth.password:admin}")
    private String configuredPassword;

    public AuthServiceImpl(JwtTokenProvider tokenProvider, JwtProperties jwtProperties) {
        this.tokenProvider = tokenProvider;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public LoginResponse authenticate(LoginRequest request) {
        if (!configuredUsername.equals(request.getUsername()) || !configuredPassword.equals(request.getPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        String token = tokenProvider.generateToken(request.getUsername(), Map.of("role", "ROLE_USER"));
        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresInMs(jwtProperties.getExpirationMs())
                .username(request.getUsername())
                .build();
    }
}
