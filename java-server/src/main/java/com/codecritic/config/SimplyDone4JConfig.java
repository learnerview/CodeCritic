package com.codecritic.config;

import com.codecritic.analysis.AnalysisStrategyFactory;
import com.codecritic.analysis.AnalysisType;
import com.codecritic.handler.AnalysisJobHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimplyDone4JConfig {

    private final AnalysisStrategyFactory strategyFactory;
    private final ApplicationEventPublisher eventPublisher;

    public SimplyDone4JConfig(AnalysisStrategyFactory strategyFactory, ApplicationEventPublisher eventPublisher) {
        this.strategyFactory = strategyFactory;
        this.eventPublisher = eventPublisher;
    }

    @Bean
    @ConditionalOnProperty(prefix = "simplydone4j", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AnalysisJobHandler complexityAnalysisHandler() {
        return new AnalysisJobHandler(AnalysisType.COMPLEXITY, strategyFactory, eventPublisher);
    }

    @Bean
    @ConditionalOnProperty(prefix = "simplydone4j", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AnalysisJobHandler bugDetectionHandler() {
        return new AnalysisJobHandler(AnalysisType.BUGS, strategyFactory, eventPublisher);
    }

    @Bean
    @ConditionalOnProperty(prefix = "simplydone4j", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AnalysisJobHandler testGenerationHandler() {
        return new AnalysisJobHandler(AnalysisType.TEST_GENERATION, strategyFactory, eventPublisher);
    }
}
