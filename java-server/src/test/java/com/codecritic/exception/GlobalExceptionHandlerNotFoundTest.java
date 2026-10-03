package com.codecritic.exception;

import com.codecritic.dto.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An unmapped URL must be a 404, not a 500.
 *
 * <p>{@link GlobalExceptionHandler} ended with a catch-all
 * {@code @ExceptionHandler(Exception.class)}, which also caught the
 * {@link NoResourceFoundException} the DispatcherServlet raises when no handler
 * matches a path. Every typo therefore returned "Internal server error",
 * hiding genuine failures behind a misleading status and logging a full stack
 * trace for what is simply a bad URL.
 *
 * <p>Found by probing {@code GET /ready} against the running container: that
 * endpoint is documented only for the Python agent, and the Java server answered
 * 500 instead of 404. Exercised directly rather than through MockMvc because
 * asserting the real DispatcherServlet wiring is what proves the fix end to end.
 */
class GlobalExceptionHandlerNotFoundTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private NoResourceFoundException unmapped(HttpMethod method, String path) {
        return new NoResourceFoundException(method, path);
    }

    @Test
    void unmappedPathReturns404Not500() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleNoResourceFound(unmapped(HttpMethod.GET, "/definitely-not-real"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void responseNamesTheMethodAndPathSoTheCallerCanCorrectIt() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleNoResourceFound(unmapped(HttpMethod.GET, "/ready"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("GET", "/ready");
    }

    @Test
    void unmappedPostIsAlso404() {
        assertThat(handler.handleNoResourceFound(unmapped(HttpMethod.POST, "/nope")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownPathUnderApiIsAlso404() {
        assertThat(handler.handleNoResourceFound(unmapped(HttpMethod.GET, "/api/not-real")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void genuineServerFaultsStillReturn500() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleGeneric(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .as("must not leak internals to the caller")
                .doesNotContain("boom");
    }
}