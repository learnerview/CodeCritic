package com.codecritic.job;

import com.codecritic.dto.ComplexityResponse;
import com.codecritic.service.AnalysisService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Behavioural tests for the Redis-outage fallback path. Every test drives the real coordinator
 * through {@code submit} / {@code getJobResult}; none assert merely that a method was called.
 */
class ResilientJobCoordinatorTest {

    private static final String JOB_TYPE = "complexity-analysis";
    private static final String PRODUCER = "alice";
    /** Must match the coordinator's retention bound; asserted literally, not reflectively. */
    private static final int MAX_LOCAL_RESULTS = 256;

    private final SimplyDoneJobCoordinator delegate = mock(SimplyDoneJobCoordinator.class);
    private final AnalysisService analysisService = mock(AnalysisService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong fakeNanos = new AtomicLong();
    private final CountDownLatch releaseWorkers = new CountDownLatch(1);

    private ResilientJobCoordinator coordinator;

    @BeforeEach
    void setUp() {
        when(analysisService.calculateComplexity(anyString())).thenReturn(new ComplexityResponse(3, 5));
        coordinator = new ResilientJobCoordinator(delegate, analysisService, objectMapper,
                60_000L, fakeNanos::get);
    }

    @AfterEach
    void tearDown() {
        releaseWorkers.countDown();
    }

    /** Makes the queue backend look down, which is what triggers local execution. */
    private void queueIsDown() {
        when(delegate.submit(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("Unable to connect to Redis"));
    }

    private static Map<String, Object> payload() {
        return Map.of("code", "public class X { void m() { int x = 1 / 0; } }");
    }

    private JobResponse awaitResult(String jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int spins = 0;
        while (System.nanoTime() < deadline) {
            Object result = coordinator.getJobResult(jobId);
            if (result != null) {
                return (JobResponse) result;
            }
            if (++spins < 2_000) {
                Thread.onSpinWait();
            } else {
                Thread.sleep(1);
                spins = 0;
            }
        }
        return null;
    }

    private String submitFallback() {
        JobSubmissionResponse response = coordinator.submit(JOB_TYPE, payload(), PRODUCER);
        assertNotNull(response.getJobId());
        return response.getJobId();
    }

    @Test
    void localResultsStayBoundedAfterManySubmissions() throws Exception {
        queueIsDown();

        List<String> jobIds = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String jobId = submitFallback();
            assertNotNull(awaitResult(jobId), "fallback job " + i + " never produced a result");
            jobIds.add(jobId);
        }

        assertTrue(coordinator.localResultCount() <= MAX_LOCAL_RESULTS,
                "retained results must be capped, was " + coordinator.localResultCount());
        assertEquals(MAX_LOCAL_RESULTS, coordinator.localResultCount(),
                "300 completed jobs should fill the cache to exactly its cap");
        assertNull(coordinator.getJobResult(jobIds.get(0)),
                "the oldest job should have been evicted from the bounded cache");
        assertNotNull(coordinator.getJobResult(jobIds.get(299)),
                "the most recent job should still be retrievable");
    }

    @Test
    void resultsWithinTtlAreServedRepeatedlyAndSurviveReReads() throws Exception {
        queueIsDown();

        String jobId = submitFallback();
        JobResponse first = awaitResult(jobId);
        assertNotNull(first);
        assertEquals("SUCCESS", first.getStatus());

        // A live entry must survive a re-read: the frontend polls until it sees SUCCESS/FAILED and
        // polls again after a reload, so consuming the entry on read would produce a 404.
        Object second = coordinator.getJobResult(jobId);
        assertNotNull(second, "a fresh result must still be served on a repeated poll");
        assertEquals(first.getResult(), ((JobResponse) second).getResult());
        assertEquals(1, coordinator.localResultCount());
    }

    @Test
    void resultsOlderThanTtlAreNotServed() throws Exception {
        queueIsDown();

        String jobId = submitFallback();
        assertNotNull(awaitResult(jobId));

        fakeNanos.addAndGet(TimeUnit.SECONDS.toNanos(61));

        assertNull(coordinator.getJobResult(jobId),
                "an expired result must not be served");
        verify(delegate, atLeastOnce()).getJobResult(jobId);
        assertEquals(0, coordinator.localResultCount(), "an expired entry should be dropped on read");
    }

    @Test
    void rateLimitExceededPropagatesWithoutLocalExecution() {
        when(delegate.submit(anyString(), any(), anyString()))
                .thenThrow(new RateLimitExceededException(60));

        assertThrows(RateLimitExceededException.class,
                () -> coordinator.submit(JOB_TYPE, payload(), PRODUCER),
                "a rate-limited job must not silently run outside the rate limit");

        verifyNoInteractions(analysisService);
        assertEquals(0, coordinator.localResultCount(), "no job may be issued or retained");
    }

    @Test
    void queueFullPropagatesWithoutLocalExecution() {
        when(delegate.submit(anyString(), any(), anyString()))
                .thenThrow(new QueueFullException(5_000));

        assertThrows(QueueFullException.class,
                () -> coordinator.submit(JOB_TYPE, payload(), PRODUCER),
                "a job shed by the queue-depth cap must not run outside that cap");

        verifyNoInteractions(analysisService);
        assertEquals(0, coordinator.localResultCount(), "no job may be issued or retained");
    }

    @Test
    void saturatedFallbackPoolRejectsInsteadOfRunningInline() throws Exception {
        queueIsDown();
        when(analysisService.calculateComplexity(anyString())).thenAnswer(invocation -> {
            releaseWorkers.await();
            return new ComplexityResponse(1, 1);
        });

        List<JobSubmissionResponse> responses = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            responses.add(coordinator.submit(JOB_TYPE, payload(), PRODUCER));
        }

        long rejected = responses.stream().filter(r -> "FAILED".equals(r.getStatus())).count();
        assertTrue(rejected >= 1,
                "a bounded pool plus bounded queue must reject the excess instead of absorbing it");

        releaseWorkers.countDown();
        verify(analysisService, atMost(54)).calculateComplexity(anyString());

        JobResponse overflow = awaitResult(responses.get(59).getJobId());
        assertNotNull(overflow, "a rejected job id must still resolve to a visible result");
        assertEquals("FAILED", overflow.getStatus());
    }

    @Test
    void failureMessageWithQuotesAndBackslashesStillProducesParseableJson() throws Exception {
        queueIsDown();
        String message = "Unexpected character '\"' in field C:\\tmp\\input.json at line 3";
        when(analysisService.calculateComplexity(anyString()))
                .thenThrow(new IllegalStateException(message));

        String jobId = submitFallback();
        JobResponse failed = awaitResult(jobId);

        assertNotNull(failed);
        assertEquals("FAILED", failed.getStatus());
        Map<String, Object> parsed = objectMapper.readValue(failed.getResult(), new TypeReference<>() { });
        assertEquals(message, parsed.get("error"),
                "quotes and backslashes must survive as data, not break the JSON");
    }

    @Test
    void successfulFallbackStillSerializesTheAnalysisResult() throws Exception {
        queueIsDown();

        String jobId = submitFallback();
        JobResponse done = awaitResult(jobId);

        assertNotNull(done);
        assertEquals("SUCCESS", done.getStatus());
        assertEquals(PRODUCER + "-" + JOB_TYPE, done.getProducer());
        Map<String, Object> parsed = objectMapper.readValue(done.getResult(), new TypeReference<>() { });
        assertEquals(3, parsed.get("cyclomaticComplexity"));
        assertEquals(5, parsed.get("cognitiveComplexity"));
    }
}