package com.codecritic.config;

import com.codecritic.security.JwtAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wildcard origins and credentials must never be enabled together.
 *
 * <p>{@code allowedOriginPatterns("*")} makes Spring reflect the caller's Origin rather
 * than sending a literal {@code *}, so {@code allowCredentials(true)} on top of it turns
 * every site on the internet into a credentialed first-party origin for {@code /**}.
 */
class WebSecurityConfigCorsTest {

    private CorsConfiguration configFor(String allowedOrigins) {
        // corsConfigurationSource() reads only the allowedOrigins field, so the security
        // collaborators are irrelevant here.
        WebSecurityConfig cfg = new WebSecurityConfig(
                org.mockito.Mockito.mock(JwtAuthFilter.class),
                new JwtProperties());
        ReflectionTestUtils.setField(cfg, "allowedOrigins", allowedOrigins);
        CorsConfigurationSource source = cfg.corsConfigurationSource();
        CorsConfiguration config = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/x"));
        assertTrue(config != null, "a CORS configuration must be registered for /**");
        return config;
    }

    @Test
    void wildcardOriginMustNotBeCombinedWithCredentials() {
        CorsConfiguration config = configFor("*");
        assertFalse(config.getAllowCredentials() != null && config.getAllowCredentials(),
                "a reflected wildcard origin must never allow credentials");
    }

    @Test
    void blankOriginFallsBackToTheSafeWildcard() {
        CorsConfiguration config = configFor("   ");
        assertFalse(config.getAllowCredentials() != null && config.getAllowCredentials(),
                "an unset origin list must not silently enable credentialed cross-origin access");
    }

    @Test
    void explicitAllowlistKeepsCredentials() {
        CorsConfiguration config = configFor("https://app.example.com, https://admin.example.com");
        assertTrue(config.getAllowCredentials(),
                "an explicit allowlist should keep credentialed requests working");
        assertTrue(config.getAllowedOrigins().contains("https://app.example.com"));
        assertTrue(config.getAllowedOrigins().contains("https://admin.example.com"));
        assertFalse(config.getAllowedOrigins().contains("*"));
    }

    @Test
    void wildcardMustNotAppearInAnExplicitAllowlist() {
        // A '*' anywhere in the list must be treated as a wildcard, not as one origin
        // among several, or the reflected-origin hole reopens alongside credentials.
        CorsConfiguration config = configFor("https://app.example.com, *");
        assertFalse(config.getAllowCredentials() != null && config.getAllowCredentials(),
                "a list containing '*' must fall back to the credential-free wildcard");
        assertEquals(List.of("*"), config.getAllowedOriginPatterns());
    }

    @Test
    void emptyEntriesAreDroppedRatherThanBecomingBlankOrigins() {
        CorsConfiguration config = configFor("https://app.example.com, ,  ");
        List<String> origins = config.getAllowedOrigins();
        assertTrue(origins.contains("https://app.example.com"));
        assertFalse(origins.contains(""), "blank entries must not become empty allowed origins");
        assertFalse(origins.contains(" "));
    }
}
