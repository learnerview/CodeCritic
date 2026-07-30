package com.codecritic.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codecritic.dto.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String expectedToken;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(String expectedToken, ObjectMapper objectMapper) {
        this.expectedToken = expectedToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (expectedToken == null || expectedToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(writeError("Missing Authorization header"));
            return;
        }

        String token = authHeader.startsWith("Bearer ")
                ? authHeader.substring(7)
                : authHeader;

        if (!expectedToken.equals(token)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(writeError("Invalid API key"));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String writeError(String message) throws IOException {
        return objectMapper.writeValueAsString(
                ApiErrorResponse.of("UNAUTHORIZED", message, null)
        );
    }
}