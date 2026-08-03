package com.codecritic.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-chain security test — verifies the real SecurityFilterChain end to end:
 * login issues a JWT, protected endpoints reject anonymous calls and accept
 * authenticated ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.codecritic.repository.UserRepository userRepository;

    @BeforeEach
    void cleanup() {
        userRepository.deleteByUsername("testuser");
    }

    @Test
    void protectedEndpoint_rejectsAnonymousRequest() throws Exception {
        mockMvc.perform(post("/api/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"public class A {}\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_rejectsInvalidToken() throws Exception {
        mockMvc.perform(post("/api/complexity")
                        .header("Authorization", "Bearer invalid.token.value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"public class A {}\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginThenCallProtectedEndpoint_succeeds() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        String token = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(loginResult.getResponse().getContentAsString())
                .get("token").asText();

        mockMvc.perform(post("/api/complexity")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"public class A { public int f(int x){ if(x>0){ return 1; } return 0; } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cyclomaticComplexity").isNumber());

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }
}