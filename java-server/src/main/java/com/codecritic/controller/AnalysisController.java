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
    public ResponseEntity<Map<String, String>> submitComplexityJob(@RequestBody ComplexityRequest req) {
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/jobs/bugs")
    public ResponseEntity<Map<String, String>> submitBugsJob(@RequestBody BugRequest req) {
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> getJobResult(@PathVariable String jobId) {
        try {
            Object response = analysisService.getJobResult(jobId);
            if (response == null) {
                return ResponseEntity.notFound().build();
            }
            if (response instanceof JobResponse job) {
                // Only the job's owner may read its payload/result (avoids IDOR).
                String expectedProducer = getProducer() + "-" + job.getJobType();
                if (!expectedProducer.equals(job.getProducer())) {
                    log.warn("User {} attempted to access job {} owned by {}", getProducer(), jobId, job.getProducer());
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to retrieve job {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/jobs/generate-test")
    public ResponseEntity<Map<String, String>> submitTestGenerationJob(@RequestBody TestGenerationRequest req) {
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
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String getProducer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null && !"anonymous".equals(auth.getPrincipal())) {
            return auth.getPrincipal().toString();
        }
        return "codecritic-anonymous";
    }
}