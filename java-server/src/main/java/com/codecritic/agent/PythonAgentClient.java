package com.codecritic.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only Java boundary to the advisory Python agent.
 *
 * <p>The browser never receives the agent URL or its credentials. The Java
 * controller authenticates the user, applies the request limits, and then
 * calls this client with a private service token.</p>
 */
@Component
public final class PythonAgentClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String serviceToken;

    public PythonAgentClient(RestClient.Builder builder,
                             ObjectMapper objectMapper,
                             @Value("${codecritic.python-agent.url:http://localhost:8000}") String url,
                             @Value("${codecritic.python-agent.token:}") String serviceToken) {
        this.client = builder.baseUrl(stripTrailingSlash(url))
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.serviceToken = serviceToken;
    }

    public JsonNode review(Object request) {
        return post("/review", request);
    }

    public JsonNode generateTests(Object request) {
        return post("/generate-tests", request);
    }

    public JsonNode analyzeRepository(com.codecritic.dto.RepositoryAnalysisRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repoUrl", request.repoUrl());
        payload.put("branch", request.branch());
        payload.put("maxFiles", request.maxFiles() == null ? 15 : request.maxFiles());
        if (request.githubToken() != null && !request.githubToken().isBlank()) {
            payload.put("githubToken", request.githubToken());
        }
        return post("/analyze-repository", payload);
    }

    public JsonNode debug(com.codecritic.dto.DebugAnalysisRequest request) {
        return post("/debug", Map.of(
                "code", request.code(),
                "errorLog", request.errorLog(),
                "language", request.language() == null || request.language().isBlank()
                        ? "java" : request.language()));
    }

    private JsonNode post(String path, Object request) {
        try {
            String body = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-CodeCritic-Agent-Token", serviceToken)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (RestClientException | java.io.IOException exception) {
            throw new PythonAgentUnavailableException("The advisory analysis service is unavailable", exception);
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8000";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public static final class PythonAgentUnavailableException extends RuntimeException {
        public PythonAgentUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
