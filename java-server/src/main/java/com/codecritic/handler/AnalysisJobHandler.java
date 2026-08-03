package com.codecritic.handler;

import com.codecritic.analysis.AnalysisStrategyFactory;
import com.codecritic.analysis.AnalysisType;
import com.codecritic.event.CodeCriticJobEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.handler.JobContext;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;

/**
 * JobHandler implementation that routes work to the registered
 * AnalysisStrategy via the factory (Strategy + Factory patterns).
 *
 * On completion it publishes a CodeCriticJobEvent so observers can react
 * (Observer pattern) without coupling the handler to any consumer.
 */
public class AnalysisJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobHandler.class);

    private final AnalysisType type;
    private final AnalysisStrategyFactory strategyFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public AnalysisJobHandler(AnalysisType type,
                              AnalysisStrategyFactory strategyFactory,
                              ApplicationEventPublisher eventPublisher) {
        this.type = type;
        this.strategyFactory = strategyFactory;
        this.eventPublisher = eventPublisher;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String handle(JobContext context) throws Exception {
        String payload = context.getPayload();
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Payload cannot be empty for job type: " + type.jobType());
        }

        Map<String, Object> payloadMap = objectMapper.readValue(payload, new TypeReference<>() {});
        Object codeObj = payloadMap.get("code");
        if (!(codeObj instanceof String code) || code.isBlank()) {
            throw new IllegalArgumentException("Missing or empty 'code' field in payload");
        }

        log.info("Processing job {} of type {} (attempt {}/{})", context.getJobId(), type.jobType(),
                context.getAttemptCount(), context.getMaxAttempts());

        Object result = strategyFactory.getStrategy(type).execute(payloadMap);

        log.info("Completed job {} of type {}", context.getJobId(), type.jobType());
        eventPublisher.publishEvent(CodeCriticJobEvent.builder()
                .type(type)
                .jobId(context.getJobId())
                .status("SUCCESS")
                .detail("Completed " + type.jobType())
                .occurredAt(Instant.now())
                .build());
        return objectMapper.writeValueAsString(result);
    }
}
