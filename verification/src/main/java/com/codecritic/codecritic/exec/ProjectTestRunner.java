package com.codecritic.exec;

import com.codecritic.change.GitException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ProjectTestRunner {

    private final ProjectAdapterFactory adapterFactory;

    public ProjectTestRunner() {
        this(new ProjectAdapterFactory());
    }

    ProjectTestRunner(ProjectAdapterFactory adapterFactory) {
        this.adapterFactory = adapterFactory;
    }

    public BaselineExecution run(
            Path repository,
            String baseRef,
            String headRef,
            ExecutionOptions options) {
        return run(repository, baseRef, headRef, options, null, false);
    }

    public BaselineExecution run(
            Path repository,
            String baseRef,
            String headRef,
            ExecutionOptions options,
            TestCandidate candidate,
            boolean stabilityCheck) {
        List<String> warnings = new ArrayList<>();
        GitWorktreeManager worktrees = new GitWorktreeManager(repository);
        BaselineExecution execution;

        try {
            Path baseWorktree = worktrees.add(baseRef);
            Path headWorktree = worktrees.add(headRef);
            TestSuiteResult base = runOne(baseWorktree, baseRef, options, candidate);
            TestSuiteResult head = runOne(headWorktree, headRef, options, candidate);

            DifferentialExecution differential = null;
            if (candidate != null) {
                DifferentialVerdict verdict = DifferentialTestClassifier.classify(base, head);
                boolean stabilityChecked = false;
                TestSuiteResult baseRepeat = null;
                TestSuiteResult headRepeat = null;
                List<String> differentialWarnings = new ArrayList<>();

                if (stabilityCheck
                        && (verdict == DifferentialVerdict.F2P
                        || verdict == DifferentialVerdict.P2F)) {
                    stabilityChecked = true;
                    baseRepeat = runOne(baseWorktree, baseRef, options, candidate);
                    headRepeat = runOne(headWorktree, headRef, options, candidate);
                    DifferentialVerdict repeat = DifferentialTestClassifier.classify(
                            baseRepeat,
                            headRepeat);
                    if (repeat != verdict) {
                        differentialWarnings.add(
                                "Candidate result was unstable: first " + verdict
                                        + ", repeat " + repeat + ".");
                        verdict = DifferentialVerdict.FLAKY;
                    }
                }

                if (verdict == DifferentialVerdict.INCONCLUSIVE) {
                    differentialWarnings.add(
                            "Differential test was inconclusive; inspect base and head statuses.");
                }
                warnings.addAll(differentialWarnings);
                differential = DifferentialExecution
                        .builder(candidate.relativePath(), candidate.className(), verdict)
                        .stability(stabilityChecked, baseRepeat, headRepeat)
                        .warnings(differentialWarnings)
                        .build();
            }

            execution = BaselineExecution.completed(base, head, differential);
        } catch (GitException e) {
            execution = BaselineExecution.setupFailed(e.getMessage());
        } finally {
            warnings.addAll(worktrees.closeAndCollect());
        }

        if (warnings.isEmpty()) {
            return execution;
        }
        List<String> combinedWarnings = new ArrayList<>(execution.warnings());
        combinedWarnings.addAll(warnings);
        return execution.withWarnings(combinedWarnings);
    }

    private TestSuiteResult runOne(
            Path worktree,
            String revision,
            ExecutionOptions options,
            TestCandidate candidate) {
        if (candidate != null) {
            try {
                inject(worktree, candidate);
            } catch (IOException e) {
                return error(revision, "Unable to inject candidate test: " + e.getMessage());
            }
        }

        return adapterFactory.detect(worktree)
                .map(adapter -> candidate == null
                        ? adapter.execute(worktree, revision, options)
                        : adapter.execute(worktree, revision, options, candidate.className()))
                .orElseGet(() -> unsupported(revision));
    }

    private static void inject(Path worktree, TestCandidate candidate) throws IOException {
        Path root = worktree.toAbsolutePath().normalize();
        Path target = root.resolve(candidate.relativePath()).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Candidate path escapes the worktree: " + candidate.relativePath());
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, candidate.source(), StandardCharsets.UTF_8);
    }

    private static TestSuiteResult unsupported(String revision) {
        String message = "No supported Maven or Gradle project found";
        return TestSuiteResult.builder(revision, "none", "", ExecutionStatus.UNSUPPORTED)
                .output(message, false)
                .warnings(List.of(message))
                .build();
    }

    private static TestSuiteResult error(String revision, String message) {
        return TestSuiteResult.builder(revision, "injection", "", ExecutionStatus.ERROR)
                .output(message, false)
                .warnings(List.of(message))
                .build();
    }
}
