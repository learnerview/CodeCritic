package com.codecritic.job;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
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

    @Override
    public Object getJobResult(String jobId) {
        log.info("Retrieving result for job {}", jobId);
        try {
            JobResponse response = jobSubmissionService.getJob(jobId);
            if ("SUCCESS".equals(response.getStatus()) && response.getResult() != null) {
                return response;
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to retrieve job {} result: {}", jobId, e.getMessage());
            return null;
        }
    }
}
