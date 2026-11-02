package com.codecritic.report;

import com.codecritic.change.ChangeSet;
import com.codecritic.change.ChangedFile;
import com.codecritic.change.MethodChange;
import com.codecritic.exec.BaselineExecution;
import com.codecritic.exec.DifferentialExecution;
import com.codecritic.exec.TestFailure;
import com.codecritic.exec.TestSuiteResult;
import com.codecritic.impact.ImpactReport;
import com.codecritic.impact.MethodImpact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class VerificationReportBuilder {

    private static final String TOOL_VERSION = "0.1.0";

    public VerificationReport build(ChangeSet changeSet, ImpactReport impact, List<String> warnings) {
        return build(changeSet, impact, warnings, null);
    }

    public VerificationReport build(
            ChangeSet changeSet,
            ImpactReport impact,
            List<String> warnings,
            BaselineExecution baseline) {
        List<Evidence> evidence = new ArrayList<>();
        EvidenceIds ids = new EvidenceIds();
        List<String> allWarnings = new ArrayList<>(warnings);
        List<String> summary = new ArrayList<>(impact.summary());

        for (MethodImpact methodImpact : impact.impacts()) {
            MethodChange change = methodImpact.change();
            int line = change.headLine() > 0 ? change.headLine() : change.baseLine();

            evidence.add(new Evidence(
                    ids.next(),
                    "METHOD_CHANGE",
                    change.path(),
                    line,
                    change.kind().name().toLowerCase(Locale.ROOT)
                            + " " + change.displayName()
                            + (change.details().isEmpty() ? "" : ": " + String.join("; ", change.details()))));

            if (change.complexityDelta() != 0) {
                evidence.add(new Evidence(
                        ids.next(),
                        "COMPLEXITY",
                        change.path(),
                        line,
                        "cyclomatic complexity " + change.baseComplexity()
                                + " -> " + change.headComplexity()));
            }

            if (methodImpact.productionCallerCount() > 0) {
                evidence.add(new Evidence(
                        ids.next(),
                        "CALLERS",
                        change.path(),
                        line,
                        methodImpact.productionCallerCount()
                                + " heuristic production caller(s) reference "
                                + change.methodName()));
            }

            if (!methodImpact.affectedTests().isEmpty()) {
                evidence.add(new Evidence(
                        ids.next(),
                        "TESTS",
                        change.path(),
                        line,
                        "affected tests: " + String.join(", ", methodImpact.affectedTests())));
            }

            evidence.add(new Evidence(
                    ids.next(),
                    "RISK",
                    change.path(),
                    line,
                    methodImpact.riskScore() + "/100 " + methodImpact.riskBand()
                            + " from " + String.join("; ", methodImpact.riskFactors())));
        }

        if (baseline != null) {
            allWarnings.addAll(baseline.warnings());
            if (baseline.base() != null) {
                allWarnings.addAll(baseline.base().warnings());
            }
            if (baseline.head() != null) {
                allWarnings.addAll(baseline.head().warnings());
            }
            if (baseline.setupError() != null) {
                allWarnings.add("Baseline test setup failed: " + baseline.setupError());
                evidence.add(new Evidence(
                        ids.next(),
                        "TEST_EXECUTION",
                        "",
                        0,
                        "baseline test setup failed: " + baseline.setupError()));
                summary.add("Baseline tests: setup failed.");
            } else {
                appendExecutionEvidence(evidence, ids, baseline.base(), "base");
                appendExecutionEvidence(evidence, ids, baseline.head(), "head");
                DifferentialExecution differential = baseline.differential();
                if (differential != null) {
                    evidence.add(new Evidence(
                            ids.next(),
                            "TEST_CANDIDATE",
                            differential.testPath(),
                            0,
                            "injected " + differential.testClass() + " into both revisions"));
                    evidence.add(new Evidence(
                            ids.next(),
                            "DIFFERENTIAL_RESULT",
                            "",
                            0,
                            differential.verdict()
                                    + ": base " + describe(baseline.base())
                                    + ", head " + describe(baseline.head())
                                    + (differential.stabilityChecked()
                                    ? ", stability check repeated the candidate"
                                    : "")));
                    summary.add("Differential test: " + differential.verdict()
                            + " for " + differential.testClass()
                            + " (base " + describe(baseline.base())
                            + "; head " + describe(baseline.head()) + ").");
                } else {
                    summary.add("Baseline tests: base " + describe(baseline.base())
                            + "; head " + describe(baseline.head()) + ".");
                }
            }
        }

        List<String> changedFiles = changeSet.files().stream()
                .map(ChangedFile::displayPath)
                .distinct()
                .sorted()
                .toList();

        return VerificationReport.builder(
                        TOOL_VERSION,
                        changeSet.repository().toString(),
                        changeSet.baseRef(),
                        changeSet.headRef(),
                        Instant.now().toString())
                .changedFiles(changedFiles)
                .methodImpacts(impact.impacts())
                .risk(impact.riskScore(), impact.riskBand())
                .summary(summary)
                .warnings(allWarnings)
                .evidence(evidence)
                .baseline(baseline)
                .build();
    }

    private static void appendExecutionEvidence(
            List<Evidence> evidence,
            EvidenceIds ids,
            TestSuiteResult result,
            String label) {
        if (result == null) {
            return;
        }
        String description = label + " " + result.revision()
                + ": " + result.status()
                + ", " + result.testsRun() + " test(s), "
                + result.failures() + " failure(s), "
                + result.errors() + " error(s)";
        if (!result.failedTests().isEmpty()) {
            TestFailure first = result.failedTests().get(0);
            description += ", first failure " + first.className() + "#" + first.testName();
        }
        evidence.add(new Evidence(ids.next(), "TEST_EXECUTION", "", 0, description));
    }

    private static String describe(TestSuiteResult result) {
        if (result == null) {
            return "not run";
        }
        return result.status() + " (" + result.testsRun() + " tests)";
    }

    private static final class EvidenceIds {
        private int next = 1;

        private String next() {
            return "E" + String.format("%02d", next++);
        }
    }
}
