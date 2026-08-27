package com.codecritic.config;

import com.codecritic.service.AnalysisService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Registers the SimplyDone4J {@link JobHandler}s with the library's
 * {@link HandlerRegistry}, routing each job type to the matching
 * {@link AnalysisService} method via the {@link JobContext} payload.
 */
@Component
@ConditionalOnProperty(prefix = "simplydone4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SimplyDoneHandlerRegistrar implements ApplicationRunner {

    static final String COMPLEXITY_JOB = "complexity-analysis";
    static final String BUGS_JOB = "bug-detection";
    static final String TESTS_JOB = "test-generation";

    private static final Logger log = LoggerFactory.getLogger(SimplyDoneHandlerRegistrar.class);

    private final HandlerRegistry handlerRegistry;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;

    public SimplyDoneHandlerRegistrar(HandlerRegistry handlerRegistry,
                                      AnalysisService analysisService,
                                      ObjectMapper objectMapper) {
        this.handlerRegistry = handlerRegistry;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        register(COMPLEXITY_JOB, this::handleComplexity);
        register(BUGS_JOB, this::handleBugs);
        register(TESTS_JOB, this::handleTestGeneration);
    }

    private void register(String jobType, io.github.learnerview.simplydone4j.handler.JobHandler handler) {
        handlerRegistry.register(jobType, handler);
        log.info("Registered SimplyDone4J handler for jobType={}", jobType);
    }

    private String handleComplexity(JobContext ctx) throws Exception {
        log.info("Executing handler for jobType={} jobId={}", COMPLEXITY_JOB, ctx.getJobId());
        return toJson(analysisService.calculateComplexity(field(ctx, "code")));
    }

    private String handleBugs(JobContext ctx) throws Exception {
        log.info("Executing handler for jobType={} jobId={}", BUGS_JOB, ctx.getJobId());
        return toJson(analysisService.findBugs(field(ctx, "code")));
    }

    private String handleTestGeneration(JobContext ctx) throws Exception {
        log.info("Executing handler for jobType={} jobId={}", TESTS_JOB, ctx.getJobId());
        return toJson(analysisService.generateTest(
                field(ctx, "className"), field(ctx, "methodName"), field(ctx, "parameters"), field(ctx, "code")));
    }

    private String toJson(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private String field(JobContext ctx, String key) {
        try {
            Map<String, Object> payload = objectMapper.readValue(ctx.getPayload(), new TypeReference<>() {});
            Object value = payload.get(key);
            return value instanceof String s ? s : "";
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid payload for job " + ctx.getJobId(), e);
        }
    }
}
