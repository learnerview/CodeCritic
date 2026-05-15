package com.codecritic.controller;

import com.codecritic.dto.ComplexityResponse;
import com.codecritic.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer smoke test for the complexity endpoint.
 *
 * Why this test matters:
 * - It verifies request mapping, JSON serialization, and the controller/service boundary.
 * - It is intentionally small and deterministic so it can run quickly in CI.
 */
@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisService analysisService;

    @Test
    void complexityEndpointReturnsMetrics() throws Exception {
        when(analysisService.calculateComplexity(anyString()))
                .thenReturn(new ComplexityResponse(3, 2));

        mockMvc.perform(post("/api/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"public class A { public int f(){ if(true){ return 1; } return 0; } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cyclomaticComplexity").value(3))
                .andExpect(jsonPath("$.cognitiveComplexity").value(2));
    }
}
