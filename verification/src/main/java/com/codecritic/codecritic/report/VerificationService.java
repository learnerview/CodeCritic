package com.codecritic.report;

import com.codecritic.change.ChangeSet;
import com.codecritic.change.ChangedFile;
import com.codecritic.change.GitChangeSource;
import com.codecritic.change.JavaDiffAnalyzer;
import com.codecritic.change.JavaDiffResult;
import com.codecritic.change.MethodChange;
import com.codecritic.change.ProjectIndex;
import com.codecritic.exec.BaselineExecution;
import com.codecritic.exec.ExecutionOptions;
import com.codecritic.exec.ProjectTestRunner;
import com.codecritic.exec.TestCandidate;
import com.codecritic.impact.ImpactAnalyzer;
import com.codecritic.impact.ImpactReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class VerificationService {

    private final GitChangeSource changeSource;
    private final JavaDiffAnalyzer diffAnalyzer;
    private final ImpactAnalyzer impactAnalyzer;
    private final VerificationReportBuilder reportBuilder;
    private final ProjectTestRunner testRunner;

    public VerificationService(
            GitChangeSource changeSource,
            JavaDiffAnalyzer diffAnalyzer,
            ImpactAnalyzer impactAnalyzer,
            VerificationReportBuilder reportBuilder,
            ProjectTestRunner testRunner) {
        this.changeSource = changeSource;
        this.diffAnalyzer = diffAnalyzer;
        this.impactAnalyzer = impactAnalyzer;
        this.reportBuilder = reportBuilder;
        this.testRunner = testRunner;
    }

    public static VerificationService createDefault() {
        return new VerificationService(
                new GitChangeSource(),
                new JavaDiffAnalyzer(),
                new ImpactAnalyzer(),
                new VerificationReportBuilder(),
                new ProjectTestRunner());
    }

    public VerificationReport verify(Path repository, String baseRef, String headRef) {
        return verify(repository, baseRef, headRef, null);
    }

    public VerificationReport verify(
            Path repository,
            String baseRef,
            String headRef,
            ExecutionOptions executionOptions) {
        return verify(repository, baseRef, headRef, executionOptions, null, false);
    }

    public VerificationReport verify(
            Path repository,
            String baseRef,
            String headRef,
            ExecutionOptions executionOptions,
            TestCandidate candidate,
            boolean stabilityCheck) {
        ChangeSet changeSet = changeSource.load(repository, baseRef, headRef);

        List<MethodChange> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ChangedFile file : changeSet.files()) {
            JavaDiffResult result = diffAnalyzer.diff(
                    file.displayPath(),
                    file.baseSource(),
                    file.headSource());
            changes.addAll(result.changes());
            warnings.addAll(result.warnings());
        }

        ProjectIndex index = ProjectIndex.from(changeSet.headSources());
        ImpactReport impact = impactAnalyzer.analyze(changes, index);
        BaselineExecution baseline = executionOptions == null
                ? null
                : testRunner.run(
                        repository,
                        baseRef,
                        headRef,
                        executionOptions,
                        candidate,
                        stabilityCheck);
        return reportBuilder.build(changeSet, impact, warnings, baseline);
    }
}
