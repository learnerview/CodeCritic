package com.codecritic.impact;

import com.codecritic.change.MethodChange;
import com.codecritic.change.MethodChangeKind;
import com.codecritic.change.ProjectIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ImpactAnalyzer {

    public ImpactReport analyze(List<MethodChange> changes, ProjectIndex index) {
        List<MethodImpact> impacts = new ArrayList<>();
        for (MethodChange change : changes) {
            int callers = index.productionCallerCount(change.key(), change.methodName());
            List<String> tests = index.affectedTests(change.className(), change.methodName());
            RiskAssessment risk = assess(change, callers, tests);
            impacts.add(new MethodImpact(change, callers, tests, risk.score(), risk.band(), risk.factors()));
        }

        impacts.sort(Comparator
                .comparingInt(MethodImpact::riskScore).reversed()
                .thenComparing(impact -> impact.change().className())
                .thenComparing(impact -> impact.change().methodName()));

        int overall = impacts.stream().mapToInt(MethodImpact::riskScore).max().orElse(0);
        return new ImpactReport(impacts, overall, band(overall), summary(changes, impacts, overall));
    }

    private RiskAssessment assess(MethodChange change, int callers, List<String> tests) {
        int score = 0;
        List<String> factors = new ArrayList<>();

        switch (change.kind()) {
            case REMOVED -> score = add(score, factors, 25, "callable removed");
            case SIGNATURE_CHANGED -> score = add(score, factors, 20, "signature changed");
            case ADDED -> score = add(score, factors, 10, "new callable");
            case MODIFIED -> score = add(score, factors, 5, "body changed");
        }

        if (change.publicApi()) {
            score = add(score, factors, 25, "public/protected API");
        }

        if (change.complexityDelta() > 0) {
            int points = Math.min(20, change.complexityDelta() * 4);
            score = add(score, factors, points,
                    "complexity +" + change.complexityDelta() + " (" + points + ")");
        }

        if (callers > 0) {
            int points = Math.min(20, callers * 4);
            score = add(score, factors, points,
                    callers + " heuristic production caller(s) (" + points + ")");
        }

        if (tests.isEmpty()) {
            score = add(score, factors, 15, "no affected tests detected");
        }

        if (change.publicApi() && callers == 0 && tests.isEmpty()) {
            score = add(score, factors, 10, "public API change without callers or tests");
        }

        int capped = Math.min(100, score);
        return new RiskAssessment(capped, band(capped), factors);
    }

    private int add(int score, List<String> factors, int points, String reason) {
        factors.add(reason + " (+" + points + ")");
        return score + points;
    }

    private RiskBand band(int score) {
        if (score >= 60) {
            return RiskBand.HIGH;
        }
        if (score >= 30) {
            return RiskBand.MEDIUM;
        }
        return RiskBand.LOW;
    }

    private List<String> summary(List<MethodChange> changes, List<MethodImpact> impacts, int overall) {
        if (changes.isEmpty()) {
            return List.of("No Java callable changes detected between the selected refs.");
        }

        long files = changes.stream().map(MethodChange::path).distinct().count();
        long publicApi = changes.stream().filter(MethodChange::publicApi).count();
        long removed = changes.stream()
                .filter(change -> change.kind() == MethodChangeKind.REMOVED)
                .count();
        long signatureChanges = changes.stream()
                .filter(change -> change.kind() == MethodChangeKind.SIGNATURE_CHANGED)
                .count();
        long tested = impacts.stream().filter(impact -> !impact.affectedTests().isEmpty()).count();

        List<String> summary = new ArrayList<>();
        summary.add(changes.size() + " changed callable(s) across " + files + " Java file(s).");
        summary.add(publicApi + " public/protected API change(s), "
                + signatureChanges + " signature change(s), "
                + removed + " removal(s).");
        summary.add(tested + " of " + impacts.size()
                + " changed callable(s) have at least one affected test.");
        summary.add("Highest risk: " + band(overall) + " (" + overall + "/100).");
        summary.add("Caller counts are name-based heuristics for prioritization, not proof of reachability.");
        return summary;
    }

    private record RiskAssessment(int score, RiskBand band, List<String> factors) {
    }
}
