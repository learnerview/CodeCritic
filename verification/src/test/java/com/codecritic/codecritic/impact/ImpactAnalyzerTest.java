package com.codecritic.impact;

import com.codecritic.change.MethodChange;
import com.codecritic.change.MethodChangeKind;
import com.codecritic.change.ProjectIndex;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactAnalyzerTest {

    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();

    @Test
    void publicSignatureChangeWithComplexityGrowthIsHighRisk() {
        MethodChange change = new MethodChange(
                "src/main/java/com/example/Calculator.java",
                "com.example.Calculator#add(int, int)",
                "com.example.Calculator",
                "add",
                MethodChangeKind.SIGNATURE_CHANGED,
                "public int add(int, int)",
                "public long add(int, int)",
                true,
                2,
                6,
                10,
                12,
                List.of("signature changed"));

        ImpactReport report = analyzer.analyze(List.of(change), ProjectIndex.from(Map.of()));

        assertThat(report.riskBand()).isEqualTo(RiskBand.HIGH);
        assertThat(report.riskScore()).isGreaterThanOrEqualTo(60);
        assertThat(report.impacts().get(0).riskFactors())
                .anyMatch(factor -> factor.contains("public/protected API"));
    }

    @Test
    void privateBodyChangeWithNoTestsIsLowRisk() {
        MethodChange change = new MethodChange(
                "src/main/java/com/example/Calculator.java",
                "com.example.Calculator#normalize(int)",
                "com.example.Calculator",
                "normalize",
                MethodChangeKind.MODIFIED,
                "private int normalize(int)",
                "private int normalize(int)",
                false,
                1,
                1,
                10,
                10,
                List.of("body changed"));

        ImpactReport report = analyzer.analyze(List.of(change), ProjectIndex.from(Map.of()));

        assertThat(report.riskBand()).isEqualTo(RiskBand.LOW);
        assertThat(report.riskScore()).isLessThan(30);
        assertThat(report.impacts().get(0).riskFactors())
                .anyMatch(factor -> factor.contains("no affected tests"));
    }

    @Test
    void recordsProductionCallersAndAffectedTestsAsEvidence() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/main/java/com/example/Service.java", """
                package com.example;

                public class Service {
                    public int run() {
                        return new Calculator().add(1, 2);
                    }
                }
                """);
        sources.put("src/test/java/com/example/CalculatorTest.java", """
                package com.example;

                class CalculatorTest {
                    void addsNumbers() {
                        new Calculator().add(1, 2);
                    }
                }
                """);

        MethodChange change = new MethodChange(
                "src/main/java/com/example/Calculator.java",
                "com.example.Calculator#add(int, int)",
                "com.example.Calculator",
                "add",
                MethodChangeKind.MODIFIED,
                "public int add(int, int)",
                "public int add(int, int)",
                true,
                1,
                2,
                10,
                11,
                List.of("body changed"));

        ImpactReport report = analyzer.analyze(List.of(change), ProjectIndex.from(sources));

        MethodImpact impact = report.impacts().get(0);
        assertThat(impact.productionCallerCount()).isEqualTo(1);
        assertThat(impact.affectedTests())
                .containsExactly("src/test/java/com/example/CalculatorTest.java");
        assertThat(impact.riskFactors())
                .noneMatch(factor -> factor.contains("no affected tests"));
    }
}
