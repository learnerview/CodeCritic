package com.codecritic.service.impl;

import com.codecritic.analysis.ComplexityAnalyzer;
import com.codecritic.analysis.BugDetector;
import com.codecritic.analysis.TestGenerator;
import com.codecritic.dto.BugReport;
import com.codecritic.dto.ComplexityResponse;
import com.codecritic.dto.TestGenerationResponse;
import com.codecritic.job.JobCoordinator;
import com.codecritic.metrics.AnalysisMetrics;
import com.codecritic.service.AnalysisService;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Facade (Structural pattern) — the single entry point the controller layer
 * needs. Delegates each concern to a focused collaborator:
 *
 * <ul>
 *   <li>Complexity -> ComplexityAnalyzer (Strategy)</li>
 *   <li>Bugs -> BugDetector (Composite of pattern + SpotBugs)</li>
 *   <li>Tests -> TestGenerator (Strategy)</li>
 *   <li>Jobs -> JobCoordinator (Adapter over SimplyDone4J)</li>
 * </ul>
 *
 * This class has no business logic of its own, satisfying SRP while keeping
 * the controller API stable.
 */
@Service
public class AnalysisServiceImpl implements AnalysisService {

    private final ComplexityAnalyzer complexityAnalyzer;
    private final BugDetector bugDetector;
    private final TestGenerator testGenerator;
    private final JobCoordinator jobCoordinator;
    private final AnalysisMetrics metrics;

    public AnalysisServiceImpl(ComplexityAnalyzer complexityAnalyzer,
                               BugDetector bugDetector,
                               TestGenerator testGenerator,
                               JobCoordinator jobCoordinator,
                               AnalysisMetrics metrics) {
        this.complexityAnalyzer = complexityAnalyzer;
        this.bugDetector = bugDetector;
        this.testGenerator = testGenerator;
        this.jobCoordinator = jobCoordinator;
        this.metrics = metrics;
    }

    @Override
    public ComplexityResponse calculateComplexity(String code) {
        long started = System.nanoTime();
        try {
            return complexityAnalyzer.analyze(code);
        } finally {
            metrics.record("complexity", (System.nanoTime() - started) / 1_000_000);
        }
    }

    @Override
    public BugReport findBugs(String code) {
        long started = System.nanoTime();
        try {
            if (bugDetector == null) {
                return new BugReport(List.of());
            }
            return new BugReport(bugDetector.detect(code));
        } finally {
            metrics.record("bugs", (System.nanoTime() - started) / 1_000_000);
        }
    }

    @Override
    public TestGenerationResponse generateTest(String className, String methodName, String parameters, String code) {
        long started = System.nanoTime();
        try {
            return testGenerator.generate(className, methodName, parameters, code);
        } finally {
            metrics.record("test-generation", (System.nanoTime() - started) / 1_000_000);
        }
    }

@Override
    public JobSubmissionResponse submitAnalysisJob(String jobType, Map<String, Object> payload, String producer) {
        return jobCoordinator.submit(jobType, payload, producer);
    }

    @Override
    public Object getJobResult(String jobId) {
        return jobCoordinator.getJobResult(jobId);
    }
}
