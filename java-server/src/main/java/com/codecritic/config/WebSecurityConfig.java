package com.codecritic.config;

import com.codecritic.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WebSecurityConfig.class);

    private final JwtAuthFilter jwtAuthFilter;
    private final JwtProperties jwtProperties;

    @Value("${codecritic.cors.allowed-origins:*}")
    private String allowedOrigins;

    public WebSecurityConfig(JwtAuthFilter jwtAuthFilter, JwtProperties jwtProperties) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.jwtProperties = jwtProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(jwtProperties.getPermitAllPaths()).permitAll()
                        .requestMatchers("/css/**", "/js/**", "/templates/**").permitAll()
                        .requestMatchers("/", "/index.html", "/review", "/review.html",
                                "/repository", "/repository.html", "/debug", "/debug.html").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"status\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid token\"}");
                }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Builds the CORS policy.
     *
     * <p>Wildcard and credentials are deliberately never enabled together.
     * {@code setAllowedOriginPatterns("*")} does not send a literal {@code *} back to the
     * browser -- it makes Spring <em>reflect whatever Origin asked</em>, so pairing it with
     * {@code allowCredentials(true)} means every site on the internet is treated as a
     * first-party origin for a credentialed request against {@code /**}. An explicit
     * allowlist keeps credentials usable; a wildcard keeps local development working but
     * drops credentials, which is the safe half of the trade.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = splitOrigins(allowedOrigins);
        // A '*' anywhere in the list -- not just as the entire value -- means wildcard.
        // Treating "https://app.example.com, *" as an explicit allowlist would keep a
        // reflected '*' origin alongside allowCredentials(true), which is the same hole.
        boolean wildcard = origins.isEmpty() || origins.contains("*");
        if (wildcard) {
            config.setAllowedOriginPatterns(List.of("*"));
            // No credentials with a reflected wildcard origin.
            config.setAllowCredentials(false);
            log.warn("CORS_ALLOW_ORIGINS resolves to a wildcard, so every origin is permitted "
                    + "without credentials. Set CORS_ALLOW_ORIGINS to a comma-separated allowlist "
                    + "for any reachable deployment.");
        } else {
            config.setAllowedOrigins(origins);
            config.setAllowCredentials(true);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static List<String> splitOrigins(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
    }
}
