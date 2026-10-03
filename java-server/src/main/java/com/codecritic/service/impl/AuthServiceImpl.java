package com.codecritic.service.impl;

import com.codecritic.config.JwtProperties;
import com.codecritic.dto.auth.LoginRequest;
import com.codecritic.dto.auth.LoginResponse;
import com.codecritic.dto.auth.RegisterRequest;
import com.codecritic.model.User;
import com.codecritic.repository.UserRepository;
import com.codecritic.security.JwtTokenProvider;
import com.codecritic.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(JwtTokenProvider tokenProvider, JwtProperties jwtProperties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.tokenProvider = tokenProvider;
        this.jwtProperties = jwtProperties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public LoginResponse authenticate(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
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

    @Override
    public LoginResponse register(RegisterRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new BadCredentialsException("Username already exists");
        }
        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .role("ROLE_USER")
                .createdAt(Instant.now())
                .build();
        try {
            userRepository.save(user);
        } catch (DuplicateKeyException e) {
            // Lost the race: the pre-check above saw no user, but a concurrent request
            // inserted the same username between the check and the insert. The unique
            // index on `users.username` is what actually makes usernames unique; the
            // pre-check is only a fast path. Surface it as a conflict rather than letting
            // it escape as an unhandled persistence failure.
            log.warn("Concurrent registration for username '{}' rejected by the unique index.",
                    request.getUsername());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        log.info("Registered new user: {}", request.getUsername());
        String token = tokenProvider.generateToken(request.getUsername(), Map.of("role", "ROLE_USER"));
        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresInMs(jwtProperties.getExpirationMs())
                .username(request.getUsername())
                .build();
    }
}
