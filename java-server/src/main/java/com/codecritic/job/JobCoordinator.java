package com.codecritic.job;

import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import java.util.Map;

/**
 * Contract for async job submission and retrieval.
 */
public interface JobCoordinator {

    JobSubmissionResponse submit(String jobType, Map<String, Object> payload, String producer);

    Object getJobResult(String jobId);
}
