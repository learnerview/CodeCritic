package com.codecritic.controller;

import com.codecritic.dto.*;
import com.codecritic.metrics.AnalysisMetrics;
import com.codecritic.service.AnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller exposing sync and async analysis endpoints used by the Python agent.
 *
 * <p>Sync endpoints ({@code /complexity}, {@code /bugs}, {@code /generate-test}) run
 * analysis immediately and return the result directly.</p>
 *
 * <p>Async endpoints ({@code /jobs/*}) submit work to the SimplyDone4J job queue
 * and return a job identifier for later retrieval.</p>
 */
@RestController
@RequestMapping("/api")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final AnalysisService analysisService;
    private final AnalysisMetrics metrics;

    public AnalysisController(AnalysisService analysisService, AnalysisMetrics metrics) {
        this.analysisService = analysisService;
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        return ResponseEntity.ok(metrics.snapshot());
    }

    @PostMapping("/complexity")
    public ResponseEntity<ComplexityResponse> complexity(@RequestBody ComplexityRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            ComplexityResponse resp = analysisService.calculateComplexity(req.code());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Complexity analysis failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/bugs")
    public ResponseEntity<BugReport> bugs(@RequestBody BugRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            BugReport report = analysisService.findBugs(req.code());
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Bug detection failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/generate-test")
    public ResponseEntity<TestGenerationResponse> generateTest(@RequestBody TestGenerationRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().build();
        }
        String className = req.className() != null ? req.className() : "";
        String methodName = req.methodName() != null ? req.methodName() : "";
        String parameters = req.parameters() != null ? req.parameters() : "";
        String code = req.code() != null ? req.code() : "";
        try {
            TestGenerationResponse resp = analysisService.generateTest(className, methodName, parameters, code);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Test generation failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/jobs/complexity")
    public ResponseEntity<?> submitComplexityJob(@RequestBody ComplexityRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            String producer = getProducer();
            JobSubmissionResponse job = analysisService.submitAnalysisJob("complexity-analysis",
                    Map.of("code", req.code()), producer);
            return ResponseEntity.accepted().body(Map.of("jobId", job.getJobId(), "status", job.getStatus()));
        } catch (Exception e) {
            log.error("Failed to submit complexity job", e);
            return internalServerError();
        }
    }

    @PostMapping("/jobs/bugs")
    public ResponseEntity<?> submitBugsJob(@RequestBody BugRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            String producer = getProducer();
            JobSubmissionResponse job = analysisService.submitAnalysisJob("bug-detection",
                    Map.of("code", req.code()), producer);
            return ResponseEntity.accepted().body(Map.of("jobId", job.getJobId(), "status", job.getStatus()));
        } catch (Exception e) {
            log.error("Failed to submit bug detection job", e);
            return internalServerError();
        }
    }

    /**
     * Returns the current state of a job, whatever that state is: queued, running, succeeded,
     * failed or dead-lettered. A 404 is reserved for a jobId that does not exist, so a client
     * polling this endpoint can tell "still working" from "finished, and it failed" — without
     * that distinction a failed job is indistinguishable from a queued one and the caller just
     * spins until it gives up.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> getJobResult(@PathVariable String jobId) {
        Object response;
        try {
            response = analysisService.getJobResult(jobId);
        } catch (RuntimeException e) {
            // Not an unknown job — the coordinator only reports those as an absent result.
            // Swallowing this would turn a backend outage into a 404 and a silent client hang,
            // so it propagates and GlobalExceptionHandler renders a sanitised 5xx.
            log.error("Failed to retrieve job {}", jobId, e);
            throw e;
        }
        if (response == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse.of("NOT_FOUND", "No such job: " + jobId, null));
        }
        if (response instanceof JobResponse job) {
            // Only the job's owner may read its payload/result (avoids IDOR).
            String expectedProducer = getProducer() + "-" + job.getJobType();
            if (!expectedProducer.equals(job.getProducer())) {
                log.warn("User {} attempted to access job {} owned by {}", getProducer(), jobId, job.getProducer());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                        ApiErrorResponse.of("FORBIDDEN", "You do not own this job", null));
            }
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/jobs/generate-test")
    public ResponseEntity<?> submitTestGenerationJob(@RequestBody TestGenerationRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().build();
        }
        String className = req.className() != null ? req.className() : "";
        String methodName = req.methodName() != null ? req.methodName() : "";
        String parameters = req.parameters() != null ? req.parameters() : "";
        String code = req.code() != null ? req.code() : "";
        try {
            String producer = getProducer();
            JobSubmissionResponse job = analysisService.submitAnalysisJob("test-generation",
                    Map.of("className", className, "methodName", methodName,
                            "parameters", parameters, "code", code), producer);
            return ResponseEntity.accepted().body(Map.of("jobId", job.getJobId(), "status", job.getStatus()));
        } catch (Exception e) {
            log.error("Failed to submit test generation job", e);
            return internalServerError();
        }
    }

    /**
     * The exception's own message can carry connection strings, hostnames and driver
     * internals, so the caller gets the same sanitised body GlobalExceptionHandler emits and
     * the real cause stays in the log where the operator wrote it.
     */
    private ResponseEntity<ApiErrorResponse> internalServerError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", null));
    }

    private String getProducer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null && !"anonymous".equals(auth.getPrincipal())) {
            return auth.getPrincipal().toString();
        }
        return "codecritic-anonymous";
    }
}