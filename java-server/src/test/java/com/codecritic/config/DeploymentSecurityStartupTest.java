package com.codecritic.config;

import com.codecritic.repository.UserRepository;
import com.codecritic.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the fix for the "known default credentials on a reachable deployment" finding at the
 * level that matters: a deployment configured with the repo's default password or the committed
 * demo JWT secret <em>fails to start</em> unless it explicitly opts into a development profile.
 *
 * <p>The previous guards only fired when a {@code prod} profile was set, so a deployment that
 * never set a profile (docker without the env var, a bare {@code java -jar}, any host that
 * forgot) silently provisioned {@code admin}/{@code admin} and signed tokens with a secret
 * published in the repo. These tests pin the default-deny behavior, including the no-profile
 * case, and pin that normal dev/test and strong-secret production configs still start.
 */
class DeploymentSecurityStartupTest {

    private static final String REPO_DEFAULT_JWT_SECRET =
            "codecritic-demo-secret-key-change-me-in-production-0123456789";
    private static final String STRONG_JWT_SECRET =
            "e2c1f0a9-b7d4-4f6e-8a2b-runtime-generated-secret";
    private static final String STRONG_PASSWORD = "Zt7#kQ2$vLm9xWp4Rb";

    private ApplicationContextRunner context(String password, String jwtSecret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(jwtSecret);
        properties.setExpirationMs(60_000L);
        properties.setIssuer("codecritic");
        return new ApplicationContextRunner()
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(PasswordEncoder.class, BCryptPasswordEncoder::new)
                .withBean(MongoTemplate.class, () -> mock(MongoTemplate.class))
                .withBean(JwtProperties.class, () -> properties)
                .withBean(DefaultUserInitializer.class)
                .withBean(JwtTokenProvider.class)
                .withPropertyValues(
                        "codecritic.auth.username=admin",
                        "codecritic.auth.password=" + password);
    }

    @Test
    void productionDeploymentFailsToStartWithTheRepoDefaultPassword() {
        ApplicationContextRunner runner = context("admin", STRONG_JWT_SECRET)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"));

        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().rootCause().hasMessageContaining("AUTH_PASSWORD");
        });
    }

    @Test
    void deploymentWithoutAnyProfileFailsToStartWithTheRepoDefaultPassword() {
        // No profile at all used to be the dangerous case: neither guard applied.
        context("admin", STRONG_JWT_SECRET).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().rootCause().hasMessageContaining("AUTH_PASSWORD");
        });
    }

    @Test
    void productionDeploymentFailsToStartWithTheRepoDefaultJwtSecret() {
        ApplicationContextRunner runner = context(STRONG_PASSWORD, REPO_DEFAULT_JWT_SECRET)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"));

        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().rootCause().hasMessageContaining("JWT_SECRET");
        });
    }

    @Test
    void deploymentWithoutAnyProfileFailsToStartWithTheRepoDefaultJwtSecret() {
        context(STRONG_PASSWORD, REPO_DEFAULT_JWT_SECRET).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().rootCause().hasMessageContaining("JWT_SECRET");
        });
    }

    @Test
    void productionDeploymentStartsWhenStrongSecretsAreConfigured() {
        ApplicationContextRunner runner = context(STRONG_PASSWORD, STRONG_JWT_SECRET)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"));

        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(JwtTokenProvider.class);
            assertThat(ctx).hasSingleBean(DefaultUserInitializer.class);
        });
    }

    @Test
    void devProfileStillAllowsTheDemoDefaults() {
        ApplicationContextRunner runner = context("admin", REPO_DEFAULT_JWT_SECRET)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"));

        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void testProfileStillAllowsTheDemoDefaults() {
        ApplicationContextRunner runner = context("admin", REPO_DEFAULT_JWT_SECRET)
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"));

        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
