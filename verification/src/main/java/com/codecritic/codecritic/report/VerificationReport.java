package com.codecritic.report;

import com.codecritic.exec.BaselineExecution;
import com.codecritic.impact.MethodImpact;
import com.codecritic.impact.RiskBand;

import java.util.ArrayList;
import java.util.List;

public record VerificationReport(
        String toolVersion,
        String repository,
        String baseRef,
        String headRef,
        String generatedAt,
        List<String> changedFiles,
        List<MethodImpact> methodImpacts,
        int riskScore,
        RiskBand riskBand,
        List<String> summary,
        List<String> warnings,
        List<Evidence> evidence,
        BaselineExecution baseline
) {
    public VerificationReport {
        changedFiles = List.copyOf(changedFiles);
        methodImpacts = List.copyOf(methodImpacts);
        summary = List.copyOf(summary);
        warnings = List.copyOf(warnings);
        evidence = List.copyOf(evidence);
    }

    public static Builder builder(
            String toolVersion,
            String repository,
            String baseRef,
            String headRef,
            String generatedAt) {
        return new Builder(toolVersion, repository, baseRef, headRef, generatedAt);
    }

    public static final class Builder {
        private final String toolVersion;
        private final String repository;
        private final String baseRef;
        private final String headRef;
        private final String generatedAt;
        private List<String> changedFiles = List.of();
        private List<MethodImpact> methodImpacts = List.of();
        private int riskScore;
        private RiskBand riskBand = RiskBand.LOW;
        private List<String> summary = List.of();
        private List<String> warnings = List.of();
        private List<Evidence> evidence = List.of();
        private BaselineExecution baseline;

        private Builder(
                String toolVersion,
                String repository,
                String baseRef,
                String headRef,
                String generatedAt) {
            this.toolVersion = toolVersion;
            this.repository = repository;
            this.baseRef = baseRef;
            this.headRef = headRef;
            this.generatedAt = generatedAt;
        }

        public Builder changedFiles(List<String> changedFiles) {
            this.changedFiles = changedFiles == null ? List.of() : new ArrayList<>(changedFiles);
            return this;
        }

        public Builder methodImpacts(List<MethodImpact> methodImpacts) {
            this.methodImpacts = methodImpacts == null ? List.of() : new ArrayList<>(methodImpacts);
            return this;
        }

        public Builder risk(int riskScore, RiskBand riskBand) {
            this.riskScore = riskScore;
            this.riskBand = riskBand;
            return this;
        }

        public Builder summary(List<String> summary) {
            this.summary = summary == null ? List.of() : new ArrayList<>(summary);
            return this;
        }

        public Builder warnings(List<String> warnings) {
            this.warnings = warnings == null ? List.of() : new ArrayList<>(warnings);
            return this;
        }

        public Builder evidence(List<Evidence> evidence) {
            this.evidence = evidence == null ? List.of() : new ArrayList<>(evidence);
            return this;
        }

        public Builder baseline(BaselineExecution baseline) {
            this.baseline = baseline;
            return this;
        }

        public VerificationReport build() {
            return new VerificationReport(
                    toolVersion,
                    repository,
                    baseRef,
                    headRef,
                    generatedAt,
                    changedFiles,
                    methodImpacts,
                    riskScore,
                    riskBand,
                    summary,
                    warnings,
                    evidence,
                    baseline);
        }
    }
}
