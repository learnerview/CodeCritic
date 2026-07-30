package com.codecritic.handler;

import com.codecritic.dto.ComplexityResponse;
import com.codecritic.dto.BugReport;
import com.codecritic.dto.TestGenerationResponse;
import com.codecritic.service.AnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.handler.JobContext;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class AnalysisJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobHandler.class);

    private final String jobType;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;

    public AnalysisJobHandler(String jobType, AnalysisService analysisService) {
        this.jobType = jobType;
        this.analysisService = analysisService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String handle(JobContext context) throws Exception {
        String payload = context.getPayload();
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Payload cannot be empty for job type: " + jobType);
        }

        Map<String, Object> payloadMap = objectMapper.readValue(payload, Map.class);
        Object codeObj = payloadMap.get("code");
        if (!(codeObj instanceof String code) || code.isBlank()) {
            throw new IllegalArgumentException("Missing or empty 'code' field in payload");
        }

        log.info("Processing job {} of type {} (attempt {}/{})", context.getJobId(), jobType,
                context.getAttemptCount(), context.getMaxAttempts());

        String result = switch (jobType) {
            case "complexity-analysis" -> {
                ComplexityResponse response = analysisService.calculateComplexity(code);
                log.info("Complexity result for job: cyclomatic={}, cognitive={}",
                        response.cyclomaticComplexity(), response.cognitiveComplexity());
                yield objectMapper.writeValueAsString(response);
            }
            case "bug-detection" -> {
                BugReport report = analysisService.findBugs(code);
                log.info("Bug detection result for job: {} findings", report.bugs().size());
                yield objectMapper.writeValueAsString(report);
            }
            case "test-generation" -> {
                String className = extractString(payloadMap, "className");
                String methodName = extractString(payloadMap, "methodName");
                String parameters = extractString(payloadMap, "parameters");
                TestGenerationResponse response = analysisService.generateTest(
                        className, methodName, parameters, code);
                log.info("Test generation result for job: {} chars generated", response.junitCode().length());
                yield objectMapper.writeValueAsString(response);
            }
            default -> throw new IllegalArgumentException("Unknown job type: " + jobType);
        };

        log.info("Completed job {} of type {}", context.getJobId(), jobType);
        return result;
    }

    private String extractString(Map<String, Object> map, String key) {
        Object val = map.getOrDefault(key, "");
        return val instanceof String s ? s : "";
    }
}