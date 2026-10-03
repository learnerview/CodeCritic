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
 * The default service account must never be provisioned with a published password
 * under the {@code prod} profile. {@code /api/**} is {@code authenticated()} and the
 * deployment is publicly reachable, so an {@code admin}/{@code admin} account that the
 * initializer creates on its own is a full-privilege credential that anyone can guess.
 *
 * <p>The bootstrap is also check-then-act, so when several replicas start at once against
 * the same Mongo they all observe "not present". The unique index on {@code users.username}
 * picks a winner and the losers must treat that as success, not as a fault.
 */
class DefaultUserInitializerTest {

private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

    private DefaultUserInitializer initializerFor(String profile, String password) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        DefaultUserInitializer init = new DefaultUserInitializer(userRepository, encoder, env, mongoTemplate);
        org.springframework.test.util.ReflectionTestUtils.setField(init, "defaultUsername", "admin");
        org.springframework.test.util.ReflectionTestUtils.setField(init, "defaultPassword", password);
        return init;
    }

    @Test
    void shouldRefuseToCreateUserInProductionWithDefaultPassword() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());

        initializerFor("prod", "admin").ensureDefaultUser();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void shouldRefuseToCreateUserInProductionWithBlankPassword() {
        initializerFor("prod", "  ").ensureDefaultUser();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void shouldRefuseToCreateUserInProductionWithOtherWellKnownDefaults() {
        for (String weak : new String[]{"password", "changeme", "secret", "123456", "ADMIN"}) {
            initializerFor("prod", weak).ensureDefaultUser();
        }

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void shouldCreateUserInProductionWithStrongConfiguredPassword() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());

        initializerFor("prod", "Zt7#kQ2$vLm9xWp4Rb").ensureDefaultUser();

        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldStillAllowLocalDevDefaultOutsideProduction() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());

        initializerFor(null, "admin").ensureDefaultUser();

        // Local dev keeps working with zero configuration.
        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldIdentifyInsecurePasswords() {
        assertTrue(DefaultUserInitializer.isInsecure(null));
        assertTrue(DefaultUserInitializer.isInsecure(""));
        assertTrue(DefaultUserInitializer.isInsecure("admin"));
        assertTrue(DefaultUserInitializer.isInsecure(" ChangeMe "));
        assertFalse(DefaultUserInitializer.isInsecure("Zt7#kQ2$vLm9xWp4Rb"));
    }

    @Test
    void shouldNotOverwriteAnExistingUser() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.of(
                User.builder().username("admin").password("x").role("ROLE_USER").build()));

        initializerFor("prod", "Zt7#kQ2$vLm9xWp4Rb").ensureDefaultUser();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void shouldEncodePasswordRatherThanStorePlaintext() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());

        initializerFor(null, "admin").ensureDefaultUser();

        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
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

        assertDoesNotThrow(() -> initializerFor(null, "admin").ensureDefaultUser());
    }

    @Test
    void shouldTreatDuplicateKeyAsAlreadyExistsUnderARealisticProdConfiguration() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());
        when(userRepository.save(any(User.class))).thenThrow(new DuplicateKeyException("E11000"));

        assertDoesNotThrow(() -> initializerFor("prod", "Zt7#kQ2$vLm9xWp4Rb").ensureDefaultUser());
    }

    /**
     * A brief Mongo outage at startup must not stop the web tier coming up; the next deploy
     * or restart retries the bootstrap.
     */
    @Test
    void shouldSurviveAMongoOutageWhenCheckingForTheUser() {
        when(userRepository.findByUsername("admin"))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertDoesNotThrow(() -> initializerFor(null, "admin").ensureDefaultUser());
    }

@Test
    void shouldSurviveAMongoOutageWhenInserting() {
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.empty());
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertDoesNotThrow(() -> initializerFor(null, "admin").ensureDefaultUser());
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

        initializerFor(null, "admin").ensureUniqueUsernameIndex();

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

        assertDoesNotThrow(() -> initializerFor(null, "admin").ensureUniqueUsernameIndex(),
                "an unreachable Mongo must not stop the application from starting");
    }

    @Test
    void shouldNotFailStartupWhenDuplicatesBlockTheUniqueIndex() {
        org.springframework.data.mongodb.core.index.IndexOperations ops =
                mock(org.springframework.data.mongodb.core.index.IndexOperations.class);
        when(mongoTemplate.indexOps(User.class)).thenReturn(ops);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("E11000 duplicate key"))
                .when(ops).ensureIndex(any(Index.class));

        assertDoesNotThrow(() -> initializerFor(null, "admin").ensureUniqueUsernameIndex());
    }

    @Test
    void shouldTolerateAMissingMongoTemplate() {
        assertDoesNotThrow(() -> new DefaultUserInitializer(userRepository, encoder,
                new MockEnvironment(), null).ensureUniqueUsernameIndex());
    }
}
