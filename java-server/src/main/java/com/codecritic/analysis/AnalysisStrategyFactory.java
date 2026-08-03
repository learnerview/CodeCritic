package com.codecritic.analysis;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Factory pattern — registers every AnalysisStrategy bean and resolves the
 * strategy for a given AnalysisType. Adding a new analysis type requires only
 * a new strategy bean; the factory itself never changes (OCP).
 */
@Component
public class AnalysisStrategyFactory {

    private final Map<AnalysisType, AnalysisStrategy> strategies = new EnumMap<>(AnalysisType.class);

    public AnalysisStrategyFactory(List<AnalysisStrategy> strategyBeans) {
        for (AnalysisStrategy strategy : strategyBeans) {
            strategies.put(strategy.type(), strategy);
        }
    }

    public AnalysisStrategy getStrategy(AnalysisType type) {
        AnalysisStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("No strategy registered for: " + type);
        }
        return strategy;
    }
}
