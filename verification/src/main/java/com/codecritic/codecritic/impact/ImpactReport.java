package com.codecritic.impact;

import java.util.List;

public record ImpactReport(
        List<MethodImpact> impacts,
        int riskScore,
        RiskBand riskBand,
        List<String> summary
) {
    public ImpactReport {
        impacts = List.copyOf(impacts);
        summary = List.copyOf(summary);
    }
}
