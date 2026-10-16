package com.codecritic.security;

import com.codecritic.config.DeploymentProfile;
import com.codecritic.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT token provider — generates and validates signed JWTs.
 *
 * Uses HMAC-SHA256 signing with a configured secret. Issuer, subject and
 * custom claims (role) are embedded in the token.
 */
@Component
public class JwtTokenProvider {

    /** Demo default shipped only under the {@code dev}/{@code test} profiles (application-dev.yml). */
    private static final String DEMO_DEFAULT_SECRET = "codecritic-demo-secret-key-change-me-in-production-0123456789";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtTokenProvider(JwtProperties properties, Environment environment) {
        this.properties = properties;
        String secret = properties.getSecret();
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("codecritic.jwt.secret must be at least 32 bytes for HS256");
        }
        if (!DeploymentProfile.allowsDemoDefaults(environment) && DEMO_DEFAULT_SECRET.equals(secret)) {
            // Default-deny: any profile that is not dev/test, and a deployment with no profile
            // at all, must not sign tokens with the committed demo secret. Requiring an explicit
            // dev/test profile means the safe behavior no longer depends on an operator
            // remembering to set a "prod" profile.
            throw new IllegalStateException(
                    "JWT_SECRET must be set to a non-default value outside the dev/test profiles. "
                            + "Refusing to start with the demo signing secret.");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generateToken(String username, Map<String, Object> claims) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.getExpirationMs());
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(username)
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public String generateToken(String username) {
        return generateToken(username, Map.of());
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getUsername(String token) {
        return parseClaims(token).getSubject();
    }
}
