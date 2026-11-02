package com.codecritic.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestResultXmlParserTest {

    @TempDir
    Path tempDir;

    private final TestResultXmlParser parser = new TestResultXmlParser();

    @Test
    void parsesPassedFailedErroredAndSkippedTests() throws Exception {
        Path results = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-com.example.CalculatorTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.CalculatorTest" tests="4">
                  <testcase name="passes" classname="com.example.CalculatorTest"/>
                  <testcase name="fails" classname="com.example.CalculatorTest">
                    <failure message="expected 2 but was 3">stack</failure>
                  </testcase>
                  <testcase name="errors" classname="com.example.CalculatorTest">
                    <error message="boom">stack</error>
                  </testcase>
                  <testcase name="skipped" classname="com.example.CalculatorTest">
                    <skipped/>
                  </testcase>
                </testsuite>
                """);

        ParsedTestResults parsed = parser.parse(List.of(results));

        assertThat(parsed.suiteFiles()).isEqualTo(1);
        assertThat(parsed.testsRun()).isEqualTo(4);
        assertThat(parsed.failures()).isEqualTo(1);
        assertThat(parsed.errors()).isEqualTo(1);
        assertThat(parsed.skipped()).isEqualTo(1);
        assertThat(parsed.failedTests()).hasSize(2);
        assertThat(parsed.failedTests().get(0).testName()).isEqualTo("fails");
        assertThat(parsed.failedTests().get(0).message()).isEqualTo("expected 2 but was 3");
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void reportsMalformedXmlAsAWarning() throws Exception {
        Path results = tempDir.resolve("build/test-results/test");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-broken.xml"), "<testsuite><testcase>");

        ParsedTestResults parsed = parser.parse(List.of(results));

        assertThat(parsed.suiteFiles()).isEqualTo(1);
        assertThat(parsed.testsRun()).isZero();
        assertThat(parsed.warnings()).hasSize(1);
        assertThat(parsed.warnings().get(0)).contains("Unable to parse test result");
    }

    @Test
    void returnsEmptyWhenThereAreNoResultDirectories() {
        ParsedTestResults parsed = parser.parse(List.of(tempDir.resolve("missing")));

        assertThat(parsed).isEqualTo(ParsedTestResults.empty());
    }
}
