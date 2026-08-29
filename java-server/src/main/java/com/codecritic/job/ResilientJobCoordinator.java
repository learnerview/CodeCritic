package com.codecritic.job;

import com.codecritic.service.AnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decorator (Structural pattern) over the SimplyDone4J-backed {@link JobCoordinator}.
 *
 * <p>The async queue is optional infrastructure (it needs MongoDB/Redis). When that backend is
 * unreachable, {@link SimplyDoneJobCoordinator#submit} throws and the caller gets a 500. This
 * decorator catches that failure and transparently falls back to running the job synchronously
 * on a background thread, storing the result in memory so the existing submit &rarr; poll flow
 * keeps working.</p>
 *
 * <p>The async API contract (submit returns a jobId, poll returns status/result) is preserved,
 * so the frontend is unaffected by whether the real queue is present.</p>
 */
@Service
@Primary
public class ResilientJobCoordinator implements JobCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ResilientJobCoordinator.class);

    private static final String COMPLEXITY_JOB = "complexity-analysis";
    private static final String BUGS_JOB = "bug-detection";
    private static final String TESTS_JOB = "test-generation";

    private final JobCoordinator delegate;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, JobResponse> localResults = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "resilient-job-");
        t.setDaemon(true);
        return t;
    });

    public ResilientJobCoordinator(SimplyDoneJobCoordinator delegate,
                                   @Lazy AnalysisService analysisService,
                                   ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

    @Override
    public JobSubmissionResponse submit(String jobType, Map<String, Object> payload, String producer) {
        try {
            return delegate.submit(jobType, payload, producer);
        } catch (Exception e) {
            log.warn("Async queue backend unavailable ({}); executing job {} synchronously as fallback",
                    e.getMessage(), jobType);
            String jobId = UUID.randomUUID().toString();
            String qualifiedProducer = producer + "-" + jobType;
            executor.submit(() -> runLocally(jobId, jobType, payload, qualifiedProducer));
            return JobSubmissionResponse.builder()
                    .jobId(jobId)
                    .status("QUEUED")
                    .jobType(jobType)
                    .build();
        }
    }

    private void runLocally(String jobId, String jobType, Map<String, Object> payload, String qualifiedProducer) {
        try {
            String result = execute(jobType, payload);
            localResults.put(jobId, JobResponse.builder()
                    .id(jobId)
                    .jobType(jobType)
                    .producer(qualifiedProducer)
                    .status("SUCCESS")
                    .result(result)
                    .build());
        } catch (Exception ex) {
            log.error("Synchronous fallback failed for job {} ({}): {}", jobId, jobType, ex.getMessage());
            localResults.put(jobId, JobResponse.builder()
                    .id(jobId)
                    .jobType(jobType)
                    .producer(qualifiedProducer)
                    .status("FAILED")
                    .result(errorJson(ex.getMessage()))
                    .build());
        }
    }

    private String execute(String jobType, Map<String, Object> payload) throws JsonProcessingException {
        return switch (jobType) {
            case COMPLEXITY_JOB ->
                    objectMapper.writeValueAsString(analysisService.calculateComplexity(str(payload, "code")));
            case BUGS_JOB ->
                    objectMapper.writeValueAsString(analysisService.findBugs(str(payload, "code")));
            case TESTS_JOB ->
                    objectMapper.writeValueAsString(analysisService.generateTest(
                            str(payload, "className"), str(payload, "methodName"),
                            str(payload, "parameters"), str(payload, "code")));
            default -> throw new IllegalArgumentException("Unsupported job type: " + jobType);
        };
    }

    private static String str(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof String s ? s : "";
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"" + message + "\"}";
        }
    }

    @Override
    public Object getJobResult(String jobId) {
        JobResponse local = localResults.get(jobId);
        if (local != null) {
            return local;
        }
        return delegate.getJobResult(jobId);
    }
}
