package com.codecritic.job;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.LinkedHashMap;
import java.util.List;
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

        assertNotEquals(key1, key2, "Different payloads must produce distinct idempotency keys");
    }

    /**
     * The old key was a flat concatenation of {@code key=value} pairs, so {@code {a="1b=2"}}
     * and {@code {a="1", b="2"}} produced the same pre-hash string and a second, different
     * request could be deduplicated onto the first job. The canonical form must not allow it.
     */
    @Test
    void structurallyDifferentPayloadsCannotCollide() {
        String jobType = "bug-detection";

        Map<String, Object> singleValueWithDelimiters = Map.of("a", "1b=2");
        Map<String, Object> twoKeys = new LinkedHashMap<>();
        twoKeys.put("a", "1");
        twoKeys.put("b", "2");

        assertNotEquals(SimplyDoneJobCoordinator.sha256Hex(jobType, singleValueWithDelimiters),
                SimplyDoneJobCoordinator.sha256Hex(jobType, twoKeys),
                "a value containing '=' and a second key must not hash to the same key");
    }

    /**
     * The old key called {@code toString()} on nested values, so a nested map and a plain
     * string that merely looked like one ({@code {b=c}}) collapsed to the same pre-hash text.
     */
    @Test
    void nestedMapAndLookalikeStringValueCannotCollide() {
        String jobType = "complexity-analysis";

        Map<String, Object> nested = Map.of("a", Map.of("b", "c"));
        Map<String, Object> lookalike = Map.of("a", "{b=c}");

        assertNotEquals(SimplyDoneJobCoordinator.sha256Hex(jobType, nested),
                SimplyDoneJobCoordinator.sha256Hex(jobType, lookalike),
                "a nested map and a string that looks like one must not hash to the same key");
    }

    @Test
    void identicalPayloadsProduceTheSameKeyRegardlessOfInsertionOrder() {
        String jobType = "complexity-analysis";

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("code", "public class A {}");
        first.put("className", "A");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("className", "A");
        second.put("code", "public class A {}");

        assertEquals(SimplyDoneJobCoordinator.sha256Hex(jobType, first),
                SimplyDoneJobCoordinator.sha256Hex(jobType, second),
                "the same payload must be idempotent whatever order its keys arrived in");
    }

    @Test
    void nestedMapInsertionOrderDoesNotChangeTheKey() {
        Map<String, Object> innerFirst = new LinkedHashMap<>();
        innerFirst.put("z", "1");
        innerFirst.put("a", "2");
        Map<String, Object> innerSecond = new LinkedHashMap<>();
        innerSecond.put("a", "2");
        innerSecond.put("z", "1");

        assertEquals(SimplyDoneJobCoordinator.sha256Hex("t", Map.of("outer", innerFirst)),
                SimplyDoneJobCoordinator.sha256Hex("t", Map.of("outer", innerSecond)),
                "nested map order is not part of the job's identity");
    }

    /** List order is meaningful, so it must stay part of the key rather than being sorted away. */
    @Test
    void listOrderIsPreservedInTheKey() {
        assertNotEquals(SimplyDoneJobCoordinator.sha256Hex("t", Map.of("x", List.of("a", "b"))),
                SimplyDoneJobCoordinator.sha256Hex("t", Map.of("x", List.of("b", "a"))),
                "two different list orders are two different payloads");
    }

    private JobResponse jobInStatus(String status, String result) {
        return JobResponse.builder()
                .id("job-1")
                .jobType("complexity-analysis")
                .producer("alice-complexity-analysis")
                .status(status)
                .result(result)
                .attemptCount(3)
                .maxAttempts(5)
                .build();
    }

    /**
     * A job that reached a terminal failure status used to come back as null, which the
     * controller answered with 404 — indistinguishable from one still sitting in the queue.
     * The poller then spun until its own budget ran out and the user saw a stall, never an
     * error, and lost the run with no explanation.
     */
    @Test
    void failedJobIsReturnedSoTheClientCanTellItFailed() {
        when(jobSubmissionService.getJob("job-1")).thenReturn(jobInStatus("FAILED", null));

        Object result = coordinator.getJobResult("job-1");

        assertInstanceOf(JobResponse.class, result,
                "A failed job must be reported, not hidden behind a not-found");
        assertEquals("FAILED", ((JobResponse) result).getStatus());
    }

    /**
     * DLQ and CANCELLED are terminal but are not statuses the client recognises, so passing
     * them through unchanged would still leave the poller waiting. They are reported as the
     * client-visible terminal-failure status instead, with the rest of the job left intact.
     */
    @Test
    void deadLetteredJobIsReportedAsATerminalFailureTheClientUnderstands() {
        when(jobSubmissionService.getJob("job-1")).thenReturn(jobInStatus("DLQ", null));

        JobResponse reported = (JobResponse) coordinator.getJobResult("job-1");

        assertNotNull(reported);
        assertEquals("FAILEDWithError", reported.getStatus());
        assertEquals("job-1", reported.getId());
        assertEquals("complexity-analysis", reported.getJobType());
        assertEquals("alice-complexity-analysis", reported.getProducer());
        assertEquals(3, reported.getAttemptCount());
        assertEquals(5, reported.getMaxAttempts());
    }

    @Test
    void cancelledJobIsAlsoReportedAsATerminalFailure() {
        when(jobSubmissionService.getJob("job-1")).thenReturn(jobInStatus("CANCELLED", "Cancelled by user"));

        JobResponse reported = (JobResponse) coordinator.getJobResult("job-1");

        assertNotNull(reported);
        assertEquals("FAILEDWithError", reported.getStatus());
        assertEquals("Cancelled by user", reported.getResult());
    }

    @Test
    void successfulJobKeepsItsSuccessStatusAndResult() {
        when(jobSubmissionService.getJob("job-1")).thenReturn(jobInStatus("SUCCESS", "{\"score\":1}"));

        JobResponse reported = (JobResponse) coordinator.getJobResult("job-1");

        assertNotNull(reported);
        assertEquals("SUCCESS", reported.getStatus());
        assertEquals("{\"score\":1}", reported.getResult());
    }

    @Test
    void stillQueuedJobIsReportedAsQueuedRatherThanAsAbsent() {
        when(jobSubmissionService.getJob("job-1")).thenReturn(jobInStatus("QUEUED", null));

        JobResponse reported = (JobResponse) coordinator.getJobResult("job-1");

        assertNotNull(reported, "A queued job exists; answering not-found is a lie");
        assertEquals("QUEUED", reported.getStatus());
    }

    /**
     * The library signals an unknown id with JobNotFoundException rather than with a null, so
     * that is the one condition the coordinator translates into "absent".
     */
    @Test
    void unknownJobIdIsReportedAsAbsent() {
        when(jobSubmissionService.getJob("no-such-job"))
                .thenThrow(new JobNotFoundException("no-such-job"));

        assertNull(coordinator.getJobResult("no-such-job"));
    }

    /**
     * A backend outage is not the same claim as "this job does not exist". Reporting one as
     * the other is what turned infrastructure failures into a silent client hang, so the
     * exception is allowed through for the web layer to turn into a 5xx.
     */
    @Test
    void infrastructureFailurePropagatesInsteadOfBecomingNotFound() {
        when(jobSubmissionService.getJob("job-1"))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to redis.internal:6379"));

        RedisConnectionFailureException thrown = assertThrows(RedisConnectionFailureException.class,
                () -> coordinator.getJobResult("job-1"));
        assertTrue(thrown.getMessage().contains("redis.internal:6379"));
    }

    @Test
    void jobImplementationReturningNullIsStillTreatedAsAbsent() {
        when(jobSubmissionService.getJob("job-1")).thenReturn(null);

        assertNull(coordinator.getJobResult("job-1"));
    }
}
