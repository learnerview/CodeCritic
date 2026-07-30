package com.codecritic.config;

import com.codecritic.handler.AnalysisJobHandler;
import com.codecritic.service.AnalysisService;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimplyDone4JConfig {

    private static final Logger log = LoggerFactory.getLogger(SimplyDone4JConfig.class);

    private final AnalysisService analysisService;
    private final HandlerRegistry handlerRegistry;

    public SimplyDone4JConfig(AnalysisService analysisService,
                                  HandlerRegistry handlerRegistry) {
        this.analysisService = analysisService;
        this.handlerRegistry = handlerRegistry;
    }

    @Bean
    @ConditionalOnProperty(prefix = "simplydone4j", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AnalysisJobHandler complexityAnalysisHandler() {
        return new AnalysisJobHandler("complexity-analysis", analysisService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "simplydone4j", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AnalysisJobHandler bugDetectionHandler() {
        return new AnalysisJobHandler("bug-detection", analysisService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "simplydone4j", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AnalysisJobHandler testGenerationHandler() {
        return new AnalysisJobHandler("test-generation", analysisService);
    }
}