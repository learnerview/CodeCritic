package com.codecritic.service;

import com.codecritic.dto.*;

/**
 * AnalysisService defines the contract for programmatic analysis of Java source code.
 *
 * Design notes:
 * - Keep the interface small and focused so it can be implemented by different backends
 *   (local analysis, remote microservice, or a mocked test implementation).
 * - Methods accept raw source text to keep transport simple; richer APIs can be added later.
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
     * The implementation may parse `code` to find precise signatures if available.
     *
     * @param className target class name (may be discovered from code if empty)
     * @param methodName target method name
     * @param parameters optional parameter definition string
     * @param code optional Java source to extract signatures
     * @return TestGenerationResponse containing JUnit source as a string
     */
    TestGenerationResponse generateTest(String className, String methodName, String parameters, String code);
}
