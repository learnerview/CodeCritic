package com.codecritic.analysis.impl;

import com.codecritic.analysis.AnalysisStrategy;
import com.codecritic.analysis.AnalysisType;
import com.codecritic.analysis.TestGenerator;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TestGenerationStrategy implements AnalysisStrategy {

    private final TestGenerator testGenerator;

    public TestGenerationStrategy(TestGenerator testGenerator) {
        this.testGenerator = testGenerator;
    }

    @Override
    public AnalysisType type() {
        return AnalysisType.TEST_GENERATION;
    }

    @Override
    public Object execute(Map<String, Object> payload) {
        String className = extractString(payload, "className");
        String methodName = extractString(payload, "methodName");
        String parameters = extractString(payload, "parameters");
        String code = extractString(payload, "code");
        return testGenerator.generate(className, methodName, parameters, code);
    }

    private String extractString(Map<String, Object> map, String key) {
        Object val = map.getOrDefault(key, "");
        return val instanceof String s ? s : "";
    }
}
