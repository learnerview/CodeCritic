package com.codecritic.report;

import com.codecritic.change.MethodChange;
import com.codecritic.change.MethodChangeKind;
import com.codecritic.exec.BaselineExecution;
import com.codecritic.exec.DifferentialExecution;
import com.codecritic.exec.DifferentialVerdict;
import com.codecritic.exec.ExecutionStatus;
import com.codecritic.exec.TestFailure;
import com.codecritic.exec.TestSuiteResult;
import com.codecritic.impact.MethodImpact;
import com.codecritic.impact.RiskBand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlReportRendererTest {

    @Test
    void escapesUntrustedReportContent() {
        MethodChange change = new MethodChange(
                "src/main/java/<img src=x>.java",
                "com.example.<script>alert(1)</script>#add",
                "com.example.<script>alert(1)</script>",
                "add",
                MethodChangeKind.MODIFIED,
                "int add(int, int)",
                "int add(int, int)",
                true,
                1,
                2,
                10,
                11,
                List.of("<script>alert(1)</script>"));
        MethodImpact impact = new MethodImpact(
                change,
                0,
                List.of("<script>test</script>"),
                75,
                RiskBand.HIGH,
                List.of("<script>factor</script>"));
        VerificationReport report = VerificationReport.builder(
                        "0.1.0", "repo", "<base>", "<head>", "2026-01-01T00:00:00Z")
                .changedFiles(List.of("<file>.java"))
                .methodImpacts(List.of(impact))
                .risk(75, RiskBand.HIGH)
                .summary(List.of("<summary>"))
                .evidence(List.of(new Evidence("E01", "TEST", "<path>", 1, "<evidence>")))
                .build();

        String html = new HtmlReportRenderer().render(report);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).doesNotContain("<img src=x>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("&lt;img src=x&gt;");
    }

    @Test
    void rendersDifferentialResultAndStabilityCheck() {
        TestSuiteResult base = TestSuiteResult.builder(
                        "base", "maven", "mvn test", ExecutionStatus.TEST_FAILURES)
                .counts(1, 1, 0, 0)
                .failedTests(List.of(new TestFailure("CalculatorTest", "adds", "expected")))
                .build();
        TestSuiteResult head = TestSuiteResult.builder(
                        "head", "maven", "mvn test", ExecutionStatus.SUCCESS)
                .counts(1, 0, 0, 0)
                .build();
        DifferentialExecution differential = DifferentialExecution.builder(
                        "src/test/java/com/example/CalculatorTest.java",
                        "com.example.CalculatorTest",
                        DifferentialVerdict.F2P)
                .stability(true, base, head)
                .warnings(List.of("Candidate <verified> twice."))
                .build();
        VerificationReport report = VerificationReport.builder(
                        "0.1.0", "repo", "main", "HEAD", "2026-01-01T00:00:00Z")
                .baseline(BaselineExecution.completed(base, head, differential))
                .build();

        String html = new HtmlReportRenderer().render(report);

        assertThat(html).contains("Differential test execution");
        assertThat(html).contains("com.example.CalculatorTest");
        assertThat(html).contains("F2P");
        assertThat(html).contains("Stability check");
        assertThat(html).contains("base repeat");
        assertThat(html).contains("Candidate &lt;verified&gt; twice.");
        assertThat(html).doesNotContain("Candidate <verified> twice.");
    }

    @Test
    void rendersBaselineExecutionWithEscapedOutput() {
        TestSuiteResult base = TestSuiteResult.builder(
                        "base", "maven", "mvn test", ExecutionStatus.SUCCESS)
                .counts(4, 0, 0, 0)
                .durationMillis(120)
                .output("base <script>alert(1)</script>", false)
                .build();
        TestSuiteResult head = TestSuiteResult.builder(
                        "head", "maven", "mvn test", ExecutionStatus.TEST_FAILURES)
                .counts(4, 1, 0, 0)
                .durationMillis(140)
                .failedTests(List.of(new TestFailure("<Test>", "fails", "<message>")))
                .output("head output", false)
                .build();
        VerificationReport report = VerificationReport.builder(
                        "0.1.0", "repo", "main", "HEAD", "2026-01-01T00:00:00Z")
                .risk(0, RiskBand.LOW)
                .summary(List.of("summary"))
                .baseline(BaselineExecution.completed(base, head, null))
                .build();

        String html = new HtmlReportRenderer().render(report);

        assertThat(html).contains("Baseline test execution");
        assertThat(html).contains("TEST_FAILURES");
        assertThat(html).contains("&lt;Test&gt;#fails");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).doesNotContain("<Test>");
    }
}
