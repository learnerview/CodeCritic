package com.codecritic.job;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class SimplyDoneJobCoordinatorTest {

    private JobSubmissionService jobSubmissionService;
    private SimplyDoneJobCoordinator coordinator;

    @BeforeEach
    void setUp() {
        jobSubmissionService = mock(JobSubmissionService.class);
        coordinator = new SimplyDoneJobCoordinator(jobSubmissionService);
    }

    @Test
    void samePayloadTwiceReturnsSameJobId() {
        String jobType = "complexity-analysis";
        Map<String, Object> payload = Map.of("code", "public class X { void m() { int x = 1 / 0; } }");

        String expectedKey = SimplyDoneJobCoordinator.sha256Hex(jobType, payload);
        String firstJobId = UUID.randomUUID().toString();

        when(jobSubmissionService.submit(anyString(), any())).thenAnswer(invocation -> {
            JobSubmissionRequest request = invocation.getArgument(1);
            String idempotencyKey = request.getIdempotencyKey();
            if (expectedKey.equals(idempotencyKey)) {
                return JobSubmissionResponse.builder()
                        .jobId(firstJobId)
                        .status("QUEUED")
                        .jobType(jobType)
                        .build();
            }
            String newJobId = UUID.randomUUID().toString();
            return JobSubmissionResponse.builder()
                    .jobId(newJobId)
                    .status("QUEUED")
                    .jobType(jobType)
                    .build();
        });

        JobSubmissionResponse firstResponse = coordinator.submit(jobType, payload, "codecritic");
        assertNotNull(firstResponse.getJobId(), "First submission should return a jobId");

        String firstJobIdResult = firstResponse.getJobId();

        JobSubmissionResponse secondResponse = coordinator.submit(jobType, payload, "codecritic");
        assertEquals(firstJobIdResult, secondResponse.getJobId(),
                "Second submission with same payload should return the same jobId");
    }

    @Test
    void differentPayloadsGetDistinctKeys() {
        String jobType = "bug-detection";

        Map<String, Object> payload1 = Map.of("code", "public class A { void m() { } }");
        Map<String, Object> payload2 = Map.of("code", "public class B { void m() { } }");

        String key1 = SimplyDoneJobCoordinator.sha256Hex(jobType, payload1);
        String key2 = SimplyDoneJobCoordinator.sha256Hex(jobType, payload2);

        assertNotEquals(key1, key2, "Different payloads must produce different idempotency keys");
    }
}