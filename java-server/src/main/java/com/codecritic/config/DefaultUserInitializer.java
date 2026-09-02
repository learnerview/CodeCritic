package com.codecritic.config;

import com.codecritic.model.User;
import com.codecritic.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Ensures the default service user (used by the Python agent and other internal
 * integrations to authenticate against this server) exists at startup. Credentials
 * come from {@code codecritic.auth.username}/{@code password} (env {@code AUTH_USERNAME}/
 * {@code AUTH_PASSWORD}), defaulting to {@code admin}/{@code admin}.
 */
@Component
public class DefaultUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${codecritic.auth.username:admin}")
    private String defaultUsername;

    @Value("${codecritic.auth.password:admin}")
    private String defaultPassword;

    public DefaultUserInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureDefaultUser();
    }

    void ensureDefaultUser() {
        try {
            if (userRepository.findByUsername(defaultUsername).isPresent()) {
                log.info("Default user '{}' already exists; skipping creation.", defaultUsername);
                return;
            }
            User user = User.builder()
                    .username(defaultUsername)
                    .password(passwordEncoder.encode(defaultPassword))
                    .role("ROLE_USER")
                    .createdAt(Instant.now())
                    .build();
            userRepository.save(user);
            log.info("Created default user '{}'.", defaultUsername);
        } catch (DataAccessException e) {
            // Mongo may not be available yet / at all; do not fail startup.
            log.warn("Could not verify/create default user '{}': {}", defaultUsername, e.getMessage());
        }
    }
}
