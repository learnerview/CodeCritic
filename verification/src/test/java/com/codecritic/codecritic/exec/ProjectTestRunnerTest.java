package com.codecritic.exec;

import com.codecritic.test.GitTestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectTestRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsUnsupportedForProjectsWithoutABuildTool() {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("repo"));
        repository.write("src/Main.java", "base");
        String base = repository.commit("base");
        repository.write("src/Main.java", "head");
        String head = repository.commit("head");

        BaselineExecution execution = new ProjectTestRunner().run(
                repository.root(),
                base,
                head,
                ExecutionOptions.defaults());

        assertThat(execution.setupError()).isNull();
        assertThat(execution.warnings()).isEmpty();
        assertThat(execution.base()).isNotNull();
        assertThat(execution.head()).isNotNull();
        assertThat(execution.base().status()).isEqualTo(ExecutionStatus.UNSUPPORTED);
        assertThat(execution.head().status()).isEqualTo(ExecutionStatus.UNSUPPORTED);
        assertThat(repository.git("worktree", "list", "--porcelain")
                .lines()
                .filter(line -> line.startsWith("worktree "))
                .count()).isEqualTo(1);
    }

    @Test
    void cleansUpWorktreesWhenTheTestProcessTimesOut() {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("repo"));
        repository.write("pom.xml", "<project/>");
        String base = repository.commit("base");
        String head = base;

        CommandRunner timeoutRunner = (directory, command, timeout, maxBytes) ->
                new ProcessResult(
                        command,
                        -1,
                        "",
                        "",
                        true,
                        false,
                        false,
                        Duration.ofMillis(300));
        ProjectAdapterFactory factory = new ProjectAdapterFactory(timeoutRunner, new TestResultXmlParser());
        BaselineExecution execution = new ProjectTestRunner(factory).run(
                repository.root(),
                base,
                head,
                ExecutionOptions.defaults());

        assertThat(execution.base().status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(execution.head().status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(repository.git("worktree", "list", "--porcelain")
                .lines()
                .filter(line -> line.startsWith("worktree "))
                .count()).isEqualTo(1);
    }

    @Test
    void injectsTheCandidateAndClassifiesAStableFailToPassTest() throws Exception {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("repo"));
        repository.write("pom.xml", "<project/>");
        String base = repository.commit("base");
        repository.write("src/main/java/Calculator.java", "head");
        String head = repository.commit("head");
        TestCandidate candidate = candidate(repository);

        AtomicInteger calls = new AtomicInteger();
        CommandRunner runner = (directory, command, timeout, maxBytes) -> {
            int call = calls.incrementAndGet();
            assertThat(directory.resolve(candidate.relativePath())).exists();
            boolean pass = call == 2 || call == 4;
            return processResult(command, pass);
        };
        ProjectAdapterFactory factory = new ProjectAdapterFactory(runner, new TestResultXmlParser());

        BaselineExecution execution = new ProjectTestRunner(factory).run(
                repository.root(),
                base,
                head,
                ExecutionOptions.defaults(),
                candidate,
                true);

        assertThat(execution.differential()).isNotNull();
        assertThat(execution.differential().verdict()).isEqualTo(DifferentialVerdict.F2P);
        assertThat(execution.differential().stabilityChecked()).isTrue();
        assertThat(execution.differential().baseRepeat().status())
                .isEqualTo(ExecutionStatus.TEST_FAILURES);
        assertThat(execution.differential().headRepeat().status())
                .isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(calls).hasValue(4);
    }

    @Test
    void rejectsAnUnstableFailToPassResultAsFlaky() throws Exception {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("repo"));
        repository.write("pom.xml", "<project/>");
        String base = repository.commit("base");
        repository.write("src/main/java/Calculator.java", "head");
        String head = repository.commit("head");
        TestCandidate candidate = candidate(repository);

        AtomicInteger calls = new AtomicInteger();
        CommandRunner runner = (directory, command, timeout, maxBytes) -> {
            int call = calls.incrementAndGet();
            return processResult(command, call != 1);
        };
        ProjectAdapterFactory factory = new ProjectAdapterFactory(runner, new TestResultXmlParser());

        BaselineExecution execution = new ProjectTestRunner(factory).run(
                repository.root(),
                base,
                head,
                ExecutionOptions.defaults(),
                candidate,
                true);

        assertThat(execution.differential().verdict()).isEqualTo(DifferentialVerdict.FLAKY);
        assertThat(execution.differential().warnings())
                .anyMatch(warning -> warning.contains("unstable"));
        assertThat(calls).hasValue(4);
    }

    @Test
    void reportsSetupFailureForAnUnknownRef() {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("repo"));
        repository.write("README.md", "base");
        String head = repository.commit("base");

        BaselineExecution execution = new ProjectTestRunner().run(
                repository.root(),
                "does-not-exist",
                head,
                ExecutionOptions.defaults());

        assertThat(execution.setupError()).contains("does-not-exist");
        assertThat(execution.base()).isNull();
        assertThat(execution.head()).isNull();
    }

    private TestCandidate candidate(GitTestRepository repository) throws Exception {
        Path source = tempDir.resolve("candidate/CalculatorTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                class CalculatorTest {
                }
                """);
        return TestCandidateLoader.load(source, repository.root(), null);
    }

    private static ProcessResult processResult(java.util.List<String> command, boolean pass) {
        String stdout = pass
                ? "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"
                : "Tests run: 1, Failures: 1, Errors: 0, Skipped: 0";
        return new ProcessResult(
                command,
                pass ? 0 : 1,
                stdout,
                "",
                false,
                false,
                false,
                Duration.ofMillis(20));
    }
}
