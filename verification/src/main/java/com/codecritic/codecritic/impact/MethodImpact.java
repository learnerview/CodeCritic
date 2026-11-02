package com.codecritic.impact;

import com.codecritic.change.MethodChange;

import java.util.List;

public record MethodImpact(
        MethodChange change,
        int productionCallerCount,
        List<String> affectedTests,
        int riskScore,
        RiskBand riskBand,
        List<String> riskFactors
) {
    public MethodImpact {
        affectedTests = List.copyOf(affectedTests);
        riskFactors = List.copyOf(riskFactors);
    }
}
