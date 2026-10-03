package com.codecritic.service.impl;

import com.codecritic.config.JwtProperties;
import com.codecritic.dto.auth.LoginResponse;
import com.codecritic.dto.auth.RegisterRequest;
import com.codecritic.model.User;
import com.codecritic.repository.UserRepository;
import com.codecritic.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration is a check-then-act: {@code findByUsername} then {@code save}. Two concurrent
 * requests for the same username both observe "not present" and both insert, which leaves two
 * documents with the same username. {@code findByUsername} then returns an arbitrary one of
 * them, so a later login can be authenticated against a stale password hash -- login outcome
 * becomes non-deterministic.
 *
 * <p>The unique index on {@code users.username} is what actually enforces uniqueness; the
 * pre-check is only a fast path. The loser of that race now gets a 409-shaped outcome
 * ({@link ResponseStatusException} carrying {@code HttpStatus.CONFLICT}) rather than a
 * {@code DuplicateKeyException} escaping as a 500.
 */
class AuthServiceImplRegistrationTest {

    private static final String USERNAME = "racer";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-that-is-at-least-32-bytes-long-1234");
        properties.setExpirationMs(60_000L);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[0]);
        authService = new AuthServiceImpl(new JwtTokenProvider(properties, env), properties,
                userRepository, passwordEncoder);
    }

    private static RegisterRequest request(String username, String password) {
        return RegisterRequest.builder().username(username).password(password).build();
    }

    @Test
    void duplicateUsernameLosingTheInsertRaceIsReportedAsConflict() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class)))
                .thenThrow(new DuplicateKeyException(
                        "E11000 duplicate key error collection: codecritic.users index: username_1 dup key: "
                                + "{ username: \"racer\" }"));

        assertThatThrownBy(() -> authService.register(request(USERNAME, "secret")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void duplicateUsernameRaceExplainsItselfToTheCaller() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error"));

        assertThatThrownBy(() -> authService.register(request(USERNAME, "secret")))
                .hasMessageContaining("already exists");
    }

    @Test
    void duplicateUsernameRaceDoesNotLeakDriverInternalsToTheCaller() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException(
                "E11000 duplicate key error collection: codecritic.users index: username_1 dup key: "
                        + "{ username: \"racer\" }"));

        assertThatThrownBy(() -> authService.register(request(USERNAME, "secret")))
                .as("the driver message names the collection and index; it is not caller-facing")
                .hasMessageNotContaining("E11000")
                .hasMessageNotContaining("username_1");
    }

    @Test
    void usernameAlreadyPresentIsRejectedWithoutInserting() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(
                User.builder().username(USERNAME).password("x").role("ROLE_USER").build()));

        assertThatThrownBy(() -> authService.register(request(USERNAME, "secret")))
                .isInstanceOf(BadCredentialsException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void freeUsernameIsPersistedWithHashedPasswordAndReturnsAToken() {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());

        LoginResponse response = authService.register(request(USERNAME, "s3cret"));

        assertThat(response.getUsername()).isEqualTo(USERNAME);
        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresInMs()).isEqualTo(60_000L);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo(USERNAME);
        assertThat(captor.getValue().getPassword())
                .as("password must never be stored in the clear")
                .isNotEqualTo("s3cret");
        assertThat(passwordEncoder.matches("s3cret", captor.getValue().getPassword())).isTrue();
        assertThat(captor.getValue().getRole()).isEqualTo("ROLE_USER");
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
    }
}