package com.codecritic.config;

import com.codecritic.model.User;
import com.codecritic.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provisioning of the default service account once startup has already accepted the
 * configured password. The decision to start at all with a given password lives in
 * {@code DeploymentSecurityStartupTest}: a well-known password outside a dev/test profile
 * must stop the context from coming up rather than merely skipping provisioning.
 *
 * <p>The bootstrap is check-then-act, so when several replicas start at once against
 * the same Mongo they all observe "not present". The unique index on {@code users.username}
 * picks a winner and the losers must treat that as success, not as a fault.
 */
class DefaultUserInitializerTest {

    private static final String STRONG_PASSWORD = "Zt7#kQ2$vLm9xWp4Rb";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

    private DefaultUserInitializer initializerFor(String profile, String password) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        return new DefaultUserInitializer(userRepository, encoder, env, mongoTemplate, "admin", password);
    }

    @Test
    void shouldCreateUserInProductionWithStrongConfiguredPassword() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());

        initializerFor("prod", STRONG_PASSWORD).ensureDefaultUser();

        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldStillAllowLocalDevDefaultUnderTheDevProfile() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());

        initializerFor("dev", "admin").ensureDefaultUser();

        // Local dev keeps working with zero configuration once it opts into the dev profile.
        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldIdentifyInsecurePasswords() {
        assertTrue(DefaultUserInitializer.isInsecure(null));
        assertTrue(DefaultUserInitializer.isInsecure(""));
        assertTrue(DefaultUserInitializer.isInsecure("admin"));
        assertTrue(DefaultUserInitializer.isInsecure(" ChangeMe "));
        assertFalse(DefaultUserInitializer.isInsecure(STRONG_PASSWORD));
    }

    @Test
    void shouldNotOverwriteAnExistingUser() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.of(
                User.builder().username("admin").password("x").role("ROLE_USER").build()));

        initializerFor("prod", STRONG_PASSWORD).ensureDefaultUser();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void shouldEncodePasswordRatherThanStorePlaintext() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());

        initializerFor("dev", "admin").ensureDefaultUser();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertFalse("admin".equals(captor.getValue().getPassword()),
                "the password must be hashed, never stored in the clear");
        assertTrue(encoder.matches("admin", captor.getValue().getPassword()));
        assertEquals("ROLE_USER", captor.getValue().getRole());
    }

    /**
     * The replica that loses the insert race must not fail startup: another instance already
     * created the account, which is exactly the state this runner was trying to reach.
     */
    @Test
    void shouldTreatDuplicateKeyAsAlreadyExists() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException(
                "E11000 duplicate key error collection: codecritic.users index: username_1 dup key: "
                        + "{ username: \"admin\" }"));

        assertDoesNotThrow(() -> initializerFor("dev", "admin").ensureDefaultUser());
    }

    @Test
    void shouldTreatDuplicateKeyAsAlreadyExistsUnderARealisticProdConfiguration() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException("E11000"));

        assertDoesNotThrow(() -> initializerFor("prod", STRONG_PASSWORD).ensureDefaultUser());
    }

    /**
     * A brief Mongo outage at startup must not stop the web tier coming up; the next deploy
     * or restart retries the bootstrap.
     */
    @Test
    void shouldSurviveAMongoOutageWhenCheckingForTheUser() {
        when(userRepository.findByUsername("admin"))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertDoesNotThrow(() -> initializerFor("dev", "admin").ensureDefaultUser());
    }

    @Test
    void shouldSurviveAMongoOutageWhenInserting() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertDoesNotThrow(() -> initializerFor("dev", "admin").ensureDefaultUser());
    }

    // --- unique index bootstrap -------------------------------------------------
    //
    // spring.data.mongodb.auto-index-creation must stay OFF: it runs inside the
    // MongoTemplate constructor, so a Mongo that is down at boot becomes a hard startup
    // failure. These tests pin that the index is created from the runner instead, and
    // that every failure mode leaves startup intact.

    @Test
    void shouldCreateUniqueIndexOnUsername() {
        org.springframework.data.mongodb.core.index.IndexOperations ops =
                mock(org.springframework.data.mongodb.core.index.IndexOperations.class);
        when(mongoTemplate.indexOps(User.class)).thenReturn(ops);

        initializerFor("dev", "admin").ensureUniqueUsernameIndex();

        ArgumentCaptor<Index> captor = ArgumentCaptor.forClass(Index.class);
        verify(ops).ensureIndex(captor.capture());
        assertTrue(Boolean.TRUE.equals(captor.getValue().getIndexOptions().get("unique")),
                "without a unique index, concurrent registration of one username inserts twice "
                        + "and login becomes non-deterministic");
        assertTrue(captor.getValue().getIndexKeys().containsKey("username"),
                "the index must be on username, which is what registration checks");
    }

    @Test
    void shouldNotFailStartupWhenMongoIsUnreachableWhileCreatingTheIndex() {
        when(mongoTemplate.indexOps(User.class))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertDoesNotThrow(() -> initializerFor("dev", "admin").ensureUniqueUsernameIndex(),
                "an unreachable Mongo must not stop the application from starting");
    }

    @Test
    void shouldNotFailStartupWhenDuplicatesBlockTheUniqueIndex() {
        org.springframework.data.mongodb.core.index.IndexOperations ops =
                mock(org.springframework.data.mongodb.core.index.IndexOperations.class);
        when(mongoTemplate.indexOps(User.class)).thenReturn(ops);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("E11000 duplicate key"))
                .when(ops).ensureIndex(any(Index.class));

        assertDoesNotThrow(() -> initializerFor("dev", "admin").ensureUniqueUsernameIndex());
    }

    @Test
    void shouldTolerateAMissingMongoTemplate() {
        assertDoesNotThrow(() -> new DefaultUserInitializer(userRepository, encoder,
                new MockEnvironment(), null, "admin", STRONG_PASSWORD).ensureUniqueUsernameIndex());
    }
}
