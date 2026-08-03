package com.codecritic.analysis.impl;

import com.codecritic.analysis.AnalysisStrategy;
import com.codecritic.analysis.AnalysisType;
import com.codecritic.analysis.ComplexityAnalyzer;
import com.codecritic.dto.ComplexityResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ComplexityStrategy implements AnalysisStrategy {

    private final ComplexityAnalyzer complexityAnalyzer;

    public ComplexityStrategy(ComplexityAnalyzer complexityAnalyzer) {
        this.complexityAnalyzer = complexityAnalyzer;
    }

    @Override
    public AnalysisType type() {
        return AnalysisType.COMPLEXITY;
    }

    @Override
    public Object execute(Map<String, Object> payload) {
        String code = (String) payload.get("code");
        return complexityAnalyzer.analyze(code);
    }
}
