package com.codecritic.config;

import com.codecritic.model.User;
import com.codecritic.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Ensures the default service user (used by the Python agent and other internal
 * integrations to authenticate against this server) exists at startup. Credentials
 * come from {@code codecritic.auth.username}/{@code password} (env {@code AUTH_USERNAME}/
 * {@code AUTH_PASSWORD}).
 *
 * <p>The local-dev default is {@code admin}/{@code admin}, which keeps a fresh clone
 * runnable under the explicit {@code dev} profile (see {@code application-dev.yml}).
 * That default is a published credential, so the constructor <em>refuses to start</em>
 * whenever {@code AUTH_PASSWORD} is unset or well-known and no development profile is
 * active. Refusing to start (rather than logging and skipping provisioning) makes the
 * misconfiguration impossible to miss and guarantees a reachable deployment is never
 * created with a publicly-known password. This mirrors the guard applied to
 * {@code JWT_SECRET} in {@code JwtTokenProvider}.
 */
@Component
public class DefaultUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserInitializer.class);

    /**
     * Passwords that must never be provisioned in production, whatever the environment
     * says. These are the defaults that end up committed to a repository.
     */
    private static final List<String> INSECURE_PASSWORDS =
            List.of("admin", "password", "changeme", "change-me", "secret", "codecritic", "123456");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MongoTemplate mongoTemplate;
    private final String defaultUsername;
    private final String defaultPassword;

    public DefaultUserInitializer(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  Environment environment,
                                  MongoTemplate mongoTemplate,
                                  @Value("${codecritic.auth.username:admin}") String defaultUsername,
                                  @Value("${codecritic.auth.password:}") String defaultPassword) {
        if (!DeploymentProfile.allowsDemoDefaults(environment) && isInsecure(defaultPassword)) {
            // Fail fast here, in the constructor, so the context never finishes starting.
            // The ApplicationRunner below runs after the web server is already listening, so
            // a guard there would let a misconfigured deployment serve requests first; a
            // constructor failure aborts startup outright and can never ship a published
            // password. The message names the one env var the operator has to set.
            throw new IllegalStateException("AUTH_PASSWORD is unset or a well-known default and no "
                    + "development profile is active. Set AUTH_PASSWORD to a unique secret (the "
                    + "Python agent uses it to obtain a token), or run with "
                    + "SPRING_PROFILES_ACTIVE=dev for local development.");
        }
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mongoTemplate = mongoTemplate;
        this.defaultUsername = defaultUsername;
        this.defaultPassword = defaultPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureUniqueUsernameIndex();
        ensureDefaultUser();
    }

    /**
     * Creates the unique index on {@code username} that {@link User} declares.
     *
     * <p>This is done here rather than by {@code spring.data.mongodb.auto-index-creation}
     * on purpose. Auto-index creation runs inside the {@code MongoTemplate} constructor,
     * which turns any Mongo that is slow or unreachable at boot into a hard startup
     * failure. Doing it from an {@link ApplicationRunner} keeps startup independent of
     * Mongo: an unavailable database is logged and retried on the next restart instead
     * of taking the whole web tier down.
     *
     * <p>Without this index, two concurrent registrations of the same username both
     * pass the existence check and both insert, leaving two documents with the same
     * username. {@code findByUsername} then returns an arbitrary one, so a user can
     * authenticate against a stale password hash.
     */
    void ensureUniqueUsernameIndex() {
        if (mongoTemplate == null) {
            return;
        }
        try {
            mongoTemplate.indexOps(User.class)
                    .ensureIndex(new Index().on("username", Direction.ASC).unique());
            log.info("Unique index on users.username is in place.");
        } catch (DuplicateKeyException e) {
            // The index is correct but cannot be built because the collection already
            // holds duplicates from the pre-index race. Say so loudly: until the
            // duplicates are removed the race is still possible.
            log.error("Cannot create the unique index on users.username because duplicate "
                    + "usernames already exist. Until they are removed, concurrent "
                    + "registration of the same username can still create duplicates and "
                    + "make login non-deterministic.");
        } catch (Exception e) {
            log.warn("Could not verify the unique index on users.username ({}: {}). Startup "
                    + "continues; registration will fall back to its pre-check until Mongo is "
                    + "reachable.", e.getClass().getSimpleName(), e.getMessage());
        }
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
        } catch (DuplicateKeyException e) {
            // Several replicas can start at once against the same Mongo and all observe
            // "not present". The unique index on users.username decides the winner; the
            // loser lands here. That is the desired end state, not a fault, so it must not
            // be logged as a warning.
            log.info("Default user '{}' was created concurrently by another instance; keeping the existing account.",
                    defaultUsername);
        } catch (DataAccessException e) {
            // Mongo may not be available yet / at all; do not fail startup. Warn (not
            // error): a transient outage on a rolling restart is expected. The exception
            // type is logged because getMessage() alone is usually just a driver message.
            log.warn("Could not verify/create default user '{}': {}: {}",
                    defaultUsername, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    static boolean isInsecure(String password) {
        return password == null
                || password.isBlank()
                || INSECURE_PASSWORDS.contains(password.trim().toLowerCase(Locale.ROOT));
    }
}
