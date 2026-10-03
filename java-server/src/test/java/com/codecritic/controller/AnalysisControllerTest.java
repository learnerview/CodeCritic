package com.codecritic.controller;

import com.codecritic.metrics.AnalysisMetrics;
import com.codecritic.security.JwtTokenProvider;
import com.codecritic.service.AnalysisService;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The polling endpoint has to be honest about a job's state.
 *
 * <p>It used to answer 404 for anything that was not a completed success, so a job that had
 * failed or been dead-lettered looked exactly like one still sitting in the queue. The
 * browser retries 60 times over two minutes and then stops without a word: the user sees a
 * stall rather than an error and loses the run with no explanation. A Redis outage looked
 * the same way too, because the coordinator swallowed every exception and reported
 * "not found" — an infrastructure failure rendered as a silent client hang.
 *
 * <p>The submit endpoints leaked the other side of the problem: they echoed
 * {@code e.getMessage()} straight into the response body, where it can carry connection
 * strings, hostnames and driver internals.
 */
@WebMvcTest(AnalysisController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalysisControllerTest {

    private static final String REDIS_DETAIL = "Unable to connect to redis.internal:6379 using driver Lettuce 6.3";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisService analysisService;

    @MockBean
    private AnalysisMetrics analysisMetrics;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    /** Unauthenticated callers are "codecritic-anonymous" to the ownership check. */
    private JobResponse jobInStatus(String status, String result) {
        return JobResponse.builder()
                .id("job-1")
                .jobType("complexity-analysis")
                .producer("codecritic-anonymous-complexity-analysis")
                .status(status)
                .result(result)
                .build();
    }

    @Test
    void failedJobIs200WithItsStatusNotA404() throws Exception {
        when(analysisService.getJobResult("job-1")).thenReturn(jobInStatus("FAILED", null));

        mockMvc.perform(get("/api/jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void deadLetteredJobIs200WithAStatusTheClientCanActOn() throws Exception {
        when(analysisService.getJobResult("job-1")).thenReturn(jobInStatus("FAILEDWithError", null));

        mockMvc.perform(get("/api/jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILEDWithError"));
    }

    @Test
    void cancelledJobIsAlsoReportedRatherThanHidden() throws Exception {
        when(analysisService.getJobResult("job-1")).thenReturn(jobInStatus("FAILEDWithError", "Cancelled by user"));

        mockMvc.perform(get("/api/jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILEDWithError"))
                .andExpect(jsonPath("$.result").value("Cancelled by user"));
    }

    @Test
    void queuedJobIs200SoTheClientKeepsWaiting() throws Exception {
        when(analysisService.getJobResult("job-1")).thenReturn(jobInStatus("QUEUED", null));

        mockMvc.perform(get("/api/jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void successfulJobStillReturnsItsResult() throws Exception {
        when(analysisService.getJobResult("job-1")).thenReturn(jobInStatus("SUCCESS", "{\"score\":1}"));

        mockMvc.perform(get("/api/jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.result").value("{\"score\":1}"));
    }

    @Test
    void unknownJobIdIsStill404() throws Exception {
        when(analysisService.getJobResult("no-such-job")).thenReturn(null);

        mockMvc.perform(get("/api/jobs/no-such-job"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUsersJobIsStillForbidden() throws Exception {
        when(analysisService.getJobResult("job-1")).thenReturn(JobResponse.builder()
                .id("job-1")
                .jobType("complexity-analysis")
                .producer("someone-else-complexity-analysis")
                .status("SUCCESS")
                .result("{}")
                .build());

        mockMvc.perform(get("/api/jobs/job-1"))
                .andExpect(status().isForbidden());
    }

    /**
     * A backend outage is not the same claim as "this job does not exist". Answering 404 here
     * is what turned an infrastructure failure into a silent two-minute client hang.
     */
    @Test
    void infrastructureFailureIs5xxNot404() throws Exception {
        when(analysisService.getJobResult("job-1"))
                .thenThrow(new RedisConnectionFailureException(REDIS_DETAIL));

        mockMvc.perform(get("/api/jobs/job-1"))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void infrastructureFailureDoesNotLeakTheConnectionDetail() throws Exception {
        when(analysisService.getJobResult("job-1"))
                .thenThrow(new RedisConnectionFailureException(REDIS_DETAIL));

        mockMvc.perform(get("/api/jobs/job-1"))
                .andExpect(jsonPath("$.status").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("redis.internal"))))
                .andExpect(content().string(not(containsString("Lettuce"))));
    }

    @Test
    void failedComplexitySubmitDoesNotLeakTheInternalMessage() throws Exception {
        when(analysisService.submitAnalysisJob(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException(REDIS_DETAIL));

        mockMvc.perform(post("/api/jobs/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"public class A { void m() { } }\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString("redis.internal"))))
                .andExpect(content().string(not(containsString("Lettuce"))));
    }

    @Test
    void failedBugSubmitUsesTheSameErrorShapeAsTheGlobalHandler() throws Exception {
        when(analysisService.submitAnalysisJob(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException(REDIS_DETAIL));

        mockMvc.perform(post("/api/jobs/bugs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"public class A { void m() { } }\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("redis.internal"))));
    }

    @Test
    void failedTestGenerationSubmitDoesNotLeakCredentials() throws Exception {
        when(analysisService.submitAnalysisJob(eq("test-generation"), any(), anyString()))
                .thenThrow(new IllegalStateException(
                        "jdbc:mongodb://admin:hunter2@db.internal:27017/codecritic refused"));

        mockMvc.perform(post("/api/jobs/generate-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"className\":\"A\",\"methodName\":\"m\",\"parameters\":\"\",\"code\":\"x\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("db.internal"))));
    }

    @Test
    void syncEndpointFailureDoesNotLeakTheInternalMessage() throws Exception {
        when(analysisService.findBugs(any()))
                .thenThrow(new IllegalStateException(
                        "jdbc:mongodb://admin:hunter2@db.internal:27017 refused"));

        mockMvc.perform(post("/api/bugs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"public class A { void m() { } }\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void successfulSubmitStillReturnsTheJobId() throws Exception {
        when(analysisService.submitAnalysisJob(anyString(), any(), anyString()))
                .thenReturn(JobSubmissionResponse.builder()
                        .jobId("job-1")
                        .status("QUEUED")
                        .jobType("complexity-analysis")
                        .build());

        mockMvc.perform(post("/api/jobs/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"public class A { void m() { } }\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void blankCodeIsRejectedBeforeAnyJobIsSubmitted() throws Exception {
        mockMvc.perform(post("/api/jobs/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedRequestBodyIsRejectedWithoutLeakingParserInternals() throws Exception {
        mockMvc.perform(post("/api/jobs/complexity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("com.fasterxml"))));
    }

    @Test
    void metricsSnapshotIsStillExposed() throws Exception {
        when(analysisMetrics.snapshot()).thenReturn(Map.of("jobs", 1));

        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs").value(1));
    }
}
