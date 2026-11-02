package com.codecritic.cli;

import com.codecritic.test.GitTestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodeCriticCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void writesAnHtmlImpactReportForARealGitRange() throws Exception {
        GitTestRepository repository = repository();

        Path report = tempDir.resolve("reports/report.html");
        int exitCode = new CommandLine(new CodeCriticCommand()).execute(
                "--repo", repository.root().toString(),
                "--base", repository.git("rev-parse", "HEAD~1").strip(),
                "--head", "HEAD",
                "--out", report.toString());

        assertThat(exitCode).isZero();
        assertThat(Files.exists(report)).isTrue();

        String html = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(html).contains("Prove the change.");
        assertThat(html).contains("Evidence ledger");
        assertThat(html).contains("com.example.Calculator#add");
        assertThat(html).contains("E01");
        assertThat(html).contains("MEDIUM");
    }

    @Test
    void writesJsonWhenRequested() throws Exception {
        GitTestRepository repository = repository();

        Path report = tempDir.resolve("report.json");
        int exitCode = new CommandLine(new CodeCriticCommand()).execute(
                "--repo", repository.root().toString(),
                "--base", repository.git("rev-parse", "HEAD~1").strip(),
                "--head", "HEAD",
                "--format", "json",
                "--out", report.toString());

        assertThat(exitCode).isZero();
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(json).contains("\"riskBand\"");
        assertThat(json).contains("\"methodImpacts\"");
        assertThat(json).contains("\"evidence\"");
    }

    @Test
    void reportsAnUnsupportedBaselineWithoutFailing() throws Exception {
        GitTestRepository repository = repository();

        Path report = tempDir.resolve("baseline.html");
        int exitCode = new CommandLine(new CodeCriticCommand()).execute(
                "--repo", repository.root().toString(),
                "--base", repository.git("rev-parse", "HEAD~1").strip(),
                "--head", "HEAD",
                "--run-tests",
                "--out", report.toString());

        assertThat(exitCode).isZero();
        String html = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(html).contains("Baseline test execution");
        assertThat(html).contains("UNSUPPORTED");
        assertThat(html).contains("No supported Maven or Gradle project found");
        assertThat(html).contains("TEST_EXECUTION");
    }

    @Test
    void includesBaselineExecutionInJson() throws Exception {
        GitTestRepository repository = repository();

        Path report = tempDir.resolve("baseline.json");
        int exitCode = new CommandLine(new CodeCriticCommand()).execute(
                "--repo", repository.root().toString(),
                "--base", repository.git("rev-parse", "HEAD~1").strip(),
                "--head", "HEAD",
                "--run-tests",
                "--format", "json",
                "--out", report.toString());

        assertThat(exitCode).isZero();
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(json).contains("\"baseline\"");
        assertThat(json).contains("UNSUPPORTED");
        assertThat(json).contains("\"TEST_EXECUTION\"");
    }

    @Test
    void reportsAnInconclusiveDifferentialTestWhenNoBuildToolIsAvailable() throws Exception {
        GitTestRepository repository = repository();
        Path candidate = tempDir.resolve("candidate/CalculatorTest.java");
        Files.createDirectories(candidate.getParent());
        Files.writeString(candidate, """
                package com.example;

                class CalculatorTest {
                    void addsNumbers() {
                        new Calculator().add(1, 2);
                    }
                }
                """);

        Path report = tempDir.resolve("differential.html");
        int exitCode = new CommandLine(new CodeCriticCommand()).execute(
                "--repo", repository.root().toString(),
                "--base", repository.git("rev-parse", "HEAD~1").strip(),
                "--head", "HEAD",
                "--test-source", candidate.toString(),
                "--out", report.toString());

        assertThat(exitCode).isZero();
        String html = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(html).contains("Differential test execution");
        assertThat(html).contains("com.example.CalculatorTest");
        assertThat(html).contains("INCONCLUSIVE");
    }

    @Test
    void includesDifferentialEvidenceInJson() throws Exception {
        GitTestRepository repository = repository();
        Path candidate = tempDir.resolve("candidate/CalculatorTest.java");
        Files.createDirectories(candidate.getParent());
        Files.writeString(candidate, """
                package com.example;

                class CalculatorTest {
                    void addsNumbers() {
                        new Calculator().add(1, 2);
                    }
                }
                """);

        Path report = tempDir.resolve("differential.json");
        int exitCode = new CommandLine(new CodeCriticCommand()).execute(
                "--repo", repository.root().toString(),
                "--base", repository.git("rev-parse", "HEAD~1").strip(),
                "--head", "HEAD",
                "--test-source", candidate.toString(),
                "--format", "json",
                "--out", report.toString());

        assertThat(exitCode).isZero();
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertThat(json).contains("\"differential\"");
        assertThat(json).contains("\"verdict\" : \"INCONCLUSIVE\"");
        assertThat(json).contains("com.example.CalculatorTest");
    }

    @Test
    void rejectsATestPathWithoutATestSource() {
        GitTestRepository repository = repository();

        int exitCode = new CommandLine(new CodeCriticCommand()).execute(
                "--repo", repository.root().toString(),
                "--base", repository.git("rev-parse", "HEAD~1").strip(),
                "--head", "HEAD",
                "--test-path", "src/test/java/com/example/CalculatorTest.java",
                "--out", tempDir.resolve("never.html").toString());

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void returnsTwoForAnUnknownRef() {
        GitTestRepository repository = repository();

        int exitCode = new CommandLine(new CodeCriticCommand()).execute(
                "--repo", repository.root().toString(),
                "--base", "does-not-exist",
                "--head", "HEAD",
                "--out", tempDir.resolve("never.html").toString());

        assertThat(exitCode).isEqualTo(2);
    }

    private GitTestRepository repository() {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("repo-" + System.nanoTime()));
        repository.write("src/main/java/com/example/Calculator.java", """
                package com.example;

                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """);
        repository.write("src/main/java/com/example/Service.java", """
                package com.example;

                public class Service {
                    public int run() {
                        return new Calculator().add(1, 2);
                    }
                }
                """);
        repository.write("src/test/java/com/example/CalculatorTest.java", """
                package com.example;

                class CalculatorTest {
                    void addsNumbers() {
                        new Calculator().add(1, 2);
                    }
                }
                """);
        repository.commit("base");

        repository.write("src/main/java/com/example/Calculator.java", """
                package com.example;

                public class Calculator {
                    public int add(int a, int b) {
                        if (a < 0) {
                            return 0;
                        }
                        return a + b;
                    }
                }
                """);
        repository.commit("head");
        return repository;
    }
}
