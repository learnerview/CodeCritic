package com.codecritic.job;

import com.codecritic.service.AnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Decorator (Structural pattern) over the SimplyDone4J-backed {@link JobCoordinator}.
 *
 * <p>The async queue is optional infrastructure (it needs MongoDB/Redis). When that backend is
 * unreachable, {@link SimplyDoneJobCoordinator#submit} throws and the caller gets a 500. This
 * decorator catches that failure and transparently falls back to running the job on a bounded
 * background pool, storing the result in memory so the existing submit &rarr; poll flow
 * keeps working.</p>
 *
 * <p>The async API contract (submit returns a jobId, poll returns status/result) is preserved,
 * so the frontend is unaffected by whether the real queue is present.</p>
 *
 * <p>Three things this decorator must not do, because a Redis outage is exactly when they bite:</p>
 * <ul>
 *   <li>grow the heap without bound &mdash; a fallback result holds the full serialized analysis
 *       payload (tens of KB for test generation), so retained results are capped and expire;</li>
 *   <li>spawn threads without bound &mdash; the fallback pool mirrors the queue's own executor
 *       sizing and rejects rather than absorbing an unbounded backlog;</li>
 *   <li>hand-construct JSON &mdash; exception messages routinely contain {@code "} and {@code \}
 *       (Jackson and Mongo messages especially), so payloads are always serialized.</li>
 * </ul>
 */
@Service
@Primary
public class ResilientJobCoordinator implements JobCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ResilientJobCoordinator.class);

    private static final String COMPLEXITY_JOB = "complexity-analysis";
    private static final String BUGS_JOB = "bug-detection";
    private static final String TESTS_JOB = "test-generation";

    /** Cap on retained fallback results, matching {@code CachedSpotBugsBugDetector}'s cache bound. */
    private static final int MAX_LOCAL_RESULTS = 256;

    /**
     * How long a fallback result stays servable. The frontend polls every 2s for at most 60
     * attempts (~2 min), so five minutes leaves generous slack while still bounding how long a
     * result can be replayed by a caller holding an old job id.
     */
    private static final long LOCAL_RESULT_TTL_MILLIS = 5 * 60 * 1000L;

    /** Fallback pool sizing, mirroring {@code simplydone4j.executor} in application.yml. */
    private static final int FALLBACK_POOL_CORE_SIZE = 2;
    private static final int FALLBACK_POOL_MAX_SIZE = 4;
    private static final int FALLBACK_QUEUE_CAPACITY = 50;
    private static final long FALLBACK_KEEP_ALIVE_SECONDS = 60;

    /**
     * A retained fallback result plus the monotonic timestamp it was stored at, so stale
     * results stop being served instead of living until eviction.
     */
    private record LocalResult(JobResponse response, long storedAtNanos) {}

    private final SimplyDoneJobCoordinator delegate;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;
    private final long localResultTtlNanos;
    private final LongSupplier nanoTime;

    /**
     * Bounded LRU of fallback results. {@code removeEldestEntry} caps the size; access-ordering
     * keeps results that are actively being polled from being evicted first. All reads and
     * writes are guarded by {@code synchronized (localResults)} because access-ordering mutates
     * the map inside {@code get}.
     */
    private final Map<String, LocalResult> localResults =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LocalResult> eldest) {
                    return size() > MAX_LOCAL_RESULTS;
                }
            });

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            FALLBACK_POOL_CORE_SIZE,
            FALLBACK_POOL_MAX_SIZE,
            FALLBACK_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(FALLBACK_QUEUE_CAPACITY),
            runnable -> {
                Thread t = new Thread(runnable, "resilient-job-");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public ResilientJobCoordinator(SimplyDoneJobCoordinator delegate,
                                   @Lazy AnalysisService analysisService,
                                   ObjectMapper objectMapper) {
        this(delegate, analysisService, objectMapper, LOCAL_RESULT_TTL_MILLIS, System::nanoTime);
    }

    /** Test seam: the TTL and the clock are injectable so expiry can be exercised deterministically. */
    ResilientJobCoordinator(SimplyDoneJobCoordinator delegate,
                            AnalysisService analysisService,
                            ObjectMapper objectMapper,
                            long localResultTtlMillis,
                            LongSupplier nanoTime) {
        this.delegate = delegate;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
        this.localResultTtlNanos = TimeUnit.MILLISECONDS.toNanos(localResultTtlMillis);
        this.nanoTime = nanoTime;
    }

    @Override
    public JobSubmissionResponse submit(String jobType, Map<String, Object> payload, String producer) {
        try {
            return delegate.submit(jobType, payload, producer);
        } catch (RateLimitExceededException | QueueFullException e) {
            // These are load-shedding decisions, not backend outages. Falling back to local
            // execution here would run the job outside the configured rate limit and queue-depth
            // cap -- precisely when those limits must hold -- so they are propagated unchanged for
            // the caller to translate into 429/503.
            log.warn("Job {} shed by queue limits ({}); not falling back to local execution",
                    jobType, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Async queue backend unavailable ({}); executing job {} synchronously as fallback",
                    e.getMessage(), jobType);
            String jobId = UUID.randomUUID().toString();
            // NOTE: unlike SimplyDoneJobCoordinator, a null/blank producer is not normalised to
            // "codecritic-anonymous" here. AnalysisController#getProducer never yields null/blank
            // (it substitutes "codecritic-anonymous"), so this is unreachable from HTTP today, and
            // AnalysisController's IDOR check builds the expected owner as getProducer() + "-" +
            // jobType -- normalising only on this side would 403 that caller. If the divergence is
            // ever fixed, qualify identically in all three places.
            String qualifiedProducer = producer + "-" + jobType;
            try {
                executor.execute(() -> runLocally(jobId, jobType, payload, qualifiedProducer));
            } catch (RejectedExecutionException saturated) {
                // The bounded fallback pool and its bounded queue are full. Record a FAILED
                // result instead of running the job inline on the caller thread: the job id has
                // already been handed out, so the poll contract must still resolve to something
                // the user can see.
                log.warn("Fallback executor saturated ({}); job {} will not run locally", jobType, jobId);
                storeLocalResult(jobId, jobType, qualifiedProducer, "FAILED",
                        errorJson("Local fallback queue is full, retry later"));
                return JobSubmissionResponse.builder()
                        .jobId(jobId)
                        .status("FAILED")
                        .jobType(jobType)
                        .build();
            }
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
            storeLocalResult(jobId, jobType, qualifiedProducer, "SUCCESS", result);
        } catch (Exception ex) {
            log.error("Synchronous fallback failed for job {} ({}): {}", jobId, jobType, ex.getMessage());
            storeLocalResult(jobId, jobType, qualifiedProducer, "FAILED", errorJson(ex.getMessage()));
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

    private void storeLocalResult(String jobId, String jobType, String qualifiedProducer,
                                  String status, String result) {
        JobResponse response = JobResponse.builder()
                .id(jobId)
                .jobType(jobType)
                .producer(qualifiedProducer)
                .status(status)
                .result(result)
                .build();
        synchronized (localResults) {
            localResults.put(jobId, new LocalResult(response, nanoTime.getAsLong()));
        }
    }

    private String errorJson(String message) {
        String text = message == null ? "Job execution failed" : message;
        try {
            return objectMapper.writeValueAsString(Map.of("error", text));
        } catch (JsonProcessingException e) {
            // Last resort must not interpolate the raw message: an unescaped quote or backslash
            // yields malformed JSON and the frontend's JSON.parse then throws, hiding the failure.
            log.warn("Could not serialize fallback error payload: {}", e.getMessage());
            return "{\"error\":\"Job execution failed\"}";
        }
    }

    /**
     * Returns the retained fallback result for {@code jobId}, or {@code null} when it was never
     * stored or has outlived the TTL. Expired entries are dropped on read; a live entry is kept
     * so a repeated poll of an already-served job keeps working (the frontend polls until it sees
     * SUCCESS/FAILED and re-polls after a page reload, and dropping on read would turn that into
     * a spurious 404).
     */
    private JobResponse localResult(String jobId) {
        if (jobId == null) {
            return null;
        }
        synchronized (localResults) {
            LocalResult entry = localResults.get(jobId);
            if (entry == null) {
                return null;
            }
            if (nanoTime.getAsLong() - entry.storedAtNanos() >= localResultTtlNanos) {
                localResults.remove(jobId);
                return null;
            }
            return entry.response();
        }
    }

    /** Number of retained fallback results; never exceeds {@link #MAX_LOCAL_RESULTS}. */
    public int localResultCount() {
        synchronized (localResults) {
            return localResults.size();
        }
    }

    @Override
    public Object getJobResult(String jobId) {
        JobResponse local = localResult(jobId);
        if (local != null) {
            return local;
        }
        return delegate.getJobResult(jobId);
    }
}