package com.codecritic.change;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIndexTest {

    @Test
    void countsProductionCallersAndFindsAffectedTests() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/main/java/com/example/Calculator.java", """
                package com.example;

                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }

                    public int compute(int a, int b) {
                        return add(a, b);
                    }
                }
                """);
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

        ProjectIndex index = ProjectIndex.from(sources);

        int callers = index.productionCallerCount(
                "com.example.Calculator#add(int, int)",
                "add");
        assertThat(callers).isEqualTo(2);
        assertThat(index.affectedTests("com.example.Calculator", "add"))
                .containsExactly("src/test/java/com/example/CalculatorTest.java");
    }

    @Test
    void recognizesTestPaths() {
        assertThat(ProjectIndex.isTestPath("src/test/java/com/example/FooTest.java")).isTrue();
        assertThat(ProjectIndex.isTestPath("src/main/java/com/example/FooTest.java")).isTrue();
        assertThat(ProjectIndex.isTestPath("src/main/java/com/example/Foo.java")).isFalse();
        assertThat(ProjectIndex.isTestPath("src/test/java/com/example/FooIT.java")).isTrue();
    }
}
