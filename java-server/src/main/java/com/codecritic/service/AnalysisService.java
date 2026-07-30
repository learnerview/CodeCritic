package com.codecritic.service;

import com.codecritic.dto.*;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import java.util.Map;

/**
 * AnalysisService defines the contract for programmatic analysis of Java source code.
 *
 * <p>Methods accept raw source text to keep transport simple;
 * richer APIs can be added later.</p>
 *
 * <p>The service also supports async job submission via SimplyDone4J for
 * long-running analysis tasks (complexity, bug detection, test generation).</p>
 */
public interface AnalysisService {

    /**
     * Compute complexity metrics for the given Java source.
     *
     * @param code Java source for analysis
     * @return ComplexityResponse containing cyclomatic and cognitive complexity estimates
     */
    ComplexityResponse calculateComplexity(String code);

    /**
     * Find potential bugs and risky patterns in the provided Java source.
     *
     * @param code Java source for analysis
     * @return BugReport with zero or more BugFinding entries
     */
    BugReport findBugs(String code);

    /**
     * Generate a JUnit test template for the specified class/method.
     * The implementation may parse {@code code} to find precise signatures if available.
     *
     * @param className target class name (may be discovered from code if empty)
     * @param methodName target method name
     * @param parameters optional parameter definition string
     * @param code optional Java source to extract signatures
     * @return TestGenerationResponse containing JUnit source as a string
     */
    TestGenerationResponse generateTest(String className, String methodName, String parameters, String code);

    /**
     * Submit an analysis job to the SimplyDone4J scheduler for async processing.
     *
     * @param jobType the type of job (complexity-analysis, bug-detection, test-generation)
     * @param payload the job payload map
     * @return JobSubmissionResponse with job identifier and status
     */
    JobSubmissionResponse submitAnalysisJob(String jobType, Map<String, Object> payload);

    /**
     * Retrieve the result of a previously submitted job.
     *
     * @param jobId the identifier of the job
     * @return the job result or null if not yet complete
     */
    Object getJobResult(String jobId);
}
