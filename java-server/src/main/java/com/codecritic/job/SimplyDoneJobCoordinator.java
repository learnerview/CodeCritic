package com.codecritic.job;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Adapter over SimplyDone4J's JobSubmissionService — isolates the third-party
 * API behind our own contract (Adapter/Structural pattern).
 */
@Service
public class SimplyDoneJobCoordinator implements JobCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SimplyDoneJobCoordinator.class);

    /**
     * Statuses after which the job will never run again. Mirrors the terminal set of the
     * library's own {@code JobStatus} enum ({@code SUCCESS}, {@code FAILED}, {@code DLQ},
     * {@code CANCELLED}); {@code QUEUED}, {@code RUNNING} and {@code RETRY_SCHEDULED} are
     * still in flight.
     */
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("SUCCESS", "FAILED", "DLQ", "CANCELLED");

    /**
     * Reported to the client in place of a terminal status the client cannot interpret.
     * {@code DLQ} (retry budget exhausted) and {@code CANCELLED} are terminal, but the
     * poller only stops on {@code SUCCESS} or {@code FAILED}, so passing them through raw
     * would leave it spinning until its own retry budget expired — the exact silent stall
     * this status mapping exists to prevent. Anything starting with {@code FAIL} is also
     * rendered as an error by the client's status styling.
     */
    private static final String TERMINAL_FAILURE_STATUS = "FAILEDWithError";

    private final JobSubmissionService jobSubmissionService;

    public SimplyDoneJobCoordinator(JobSubmissionService jobSubmissionService) {
        this.jobSubmissionService = jobSubmissionService;
    }

    @Override
    public JobSubmissionResponse submit(String jobType, Map<String, Object> payload, String producer) {
        log.info("Submitting {} job to SimplyDone4J", jobType);
        String idempotencyKey = sha256Hex(jobType, payload);
        // Qualify the producer with the job type so SimplyDone4J's per-producer
        // rate limiter (60 requests/min default) is not shared across unrelated
        // job types from the same originating identity. Keeping the identity
        // prefix stable still lets content-based idempotency dedupe retries.
        String qualifiedProducer = producer + "-" + jobType;
        if (producer == null || producer.isBlank()) {
            qualifiedProducer = "codecritic-anonymous" + "-" + jobType;
        }
        JobSubmissionRequest request = new JobSubmissionRequest();
        request.setJobType(jobType);
        request.setIdempotencyKey(idempotencyKey);
        request.setPayload(payload);
        return jobSubmissionService.submit(qualifiedProducer, request);
    }

    static String sha256Hex(String jobType, Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        sb.append(jobType);
        for (Map.Entry<String, Object> entry : new TreeMap<>(payload).entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return computeSha256(sb.toString());
    }

    private static String computeSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexBuilder = new StringBuilder();
            for (byte b : hash) {
                hexBuilder.append(String.format("%02x", b));
            }
            return hexBuilder.toString();
        } catch (Exception e) {
            log.warn("SHA-256 computation failed, using deterministic fallback key: {}", e.getMessage());
            return "codecritic-fallback-" + input.hashCode();
        }
    }

    /**
     * Look up a job. Returns the {@link JobResponse} for <em>every</em> job that exists,
     * whatever its status, and {@code null} only when the job genuinely does not exist.
     *
     * <p>Collapsing a non-terminal or terminal-failure status to {@code null} made a failed
     * job indistinguishable from one that was still queued, so the caller could only ever
     * answer 404 and the poller could not distinguish "keep waiting" from "give up".</p>
     *
     * @return the job's current state, or {@code null} if no such job exists
     */
    @Override
    public Object getJobResult(String jobId) {
        JobResponse response;
        try {
            response = jobSubmissionService.getJob(jobId);
        } catch (JobNotFoundException e) {
            // The library implements getJob as findById(id).orElseThrow(() -> new
            // JobNotFoundException(id)), so an unknown id surfaces as this exception rather
            // than as a null. That makes it the only condition that justifies a 404. Any
            // other exception leaving getJob — a Redis outage, a command timeout, a
            // deserialization fault — is an infrastructure failure and is deliberately left
            // to propagate so the caller answers 5xx instead of claiming the job is unknown.
            log.info("Job {} not found", jobId);
            return null;
        }
        if (response == null) {
            return null;
        }
        if (TERMINAL_STATUSES.contains(response.getStatus())) {
            return withClientVisibleStatus(response);
        }
        return response;
    }

    /**
     * JobResponse is immutable (all fields final, no setters), so a terminal status the
     * client cannot act on is rewritten by rebuilding it field for field — everything the
     * caller already receives for a successful job stays byte-identical apart from status.
     */
    private JobResponse withClientVisibleStatus(JobResponse response) {
        String status = response.getStatus();
        if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
            return response;
        }
        log.info("Job {} finished in terminal status {}; reporting as {}", response.getId(), status,
                TERMINAL_FAILURE_STATUS);
        return JobResponse.builder()
                .id(response.getId())
                .jobType(response.getJobType())
                .producer(response.getProducer())
                .idempotencyKey(response.getIdempotencyKey())
                .status(TERMINAL_FAILURE_STATUS)
                .priority(response.getPriority())
                .payload(response.getPayload())
                .result(response.getResult())
                .nextRunAt(response.getNextRunAt())
                .visibleAt(response.getVisibleAt())
                .leaseOwner(response.getLeaseOwner())
                .timeoutSeconds(response.getTimeoutSeconds())
                .callbackUrl(response.getCallbackUrl())
                .startedAt(response.getStartedAt())
                .completedAt(response.getCompletedAt())
                .attemptCount(response.getAttemptCount())
                .maxAttempts(response.getMaxAttempts())
                .createdAt(response.getCreatedAt())
                .updatedAt(response.getUpdatedAt())
                .build();
    }
}
