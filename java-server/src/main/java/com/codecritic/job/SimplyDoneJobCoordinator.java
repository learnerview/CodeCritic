package com.codecritic.job;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

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
    public JobSubmissionResponse submit(String jobType, Map<String, Object> payload) {
        log.info("Submitting {} job to SimplyDone4J", jobType);
        JobSubmissionRequest request = new JobSubmissionRequest();
        request.setJobType(jobType);
        request.setIdempotencyKey(UUID.randomUUID().toString());
        request.setPayload(payload);
        return jobSubmissionService.submit("codecritic", request);
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
