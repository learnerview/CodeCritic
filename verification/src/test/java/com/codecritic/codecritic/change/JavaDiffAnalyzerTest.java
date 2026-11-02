package com.codecritic.change;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaDiffAnalyzerTest {

    private final JavaDiffAnalyzer analyzer = new JavaDiffAnalyzer();

    @Test
    void detectsBodyChangeAndComplexityIncrease() {
        String base = """
                package com.example;

                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        String head = """
                package com.example;

                public class Calculator {
                    public int add(int a, int b) {
                        if (a < 0) {
                            return 0;
                        }
                        return a + b;
                    }
                }
                """;

        JavaDiffResult result = analyzer.diff("Calculator.java", base, head);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.changes()).hasSize(1);
        MethodChange change = result.changes().get(0);
        assertThat(change.kind()).isEqualTo(MethodChangeKind.MODIFIED);
        assertThat(change.displayName()).isEqualTo("com.example.Calculator#add");
        assertThat(change.baseComplexity()).isEqualTo(1);
        assertThat(change.headComplexity()).isEqualTo(2);
        assertThat(change.complexityDelta()).isEqualTo(1);
        assertThat(change.publicApi()).isTrue();
    }

    @Test
    void detectsSignatureAndVisibilityChanges() {
        String base = """
                package com.example;

                public class Calculator {
                    public long add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        String head = """
                package com.example;

                public class Calculator {
                    private int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        JavaDiffResult result = analyzer.diff("Calculator.java", base, head);

        assertThat(result.changes()).hasSize(1);
        MethodChange change = result.changes().get(0);
        assertThat(change.kind()).isEqualTo(MethodChangeKind.SIGNATURE_CHANGED);
        assertThat(change.publicApi()).isTrue();
        assertThat(change.details()).anyMatch(detail -> detail.contains("public API visibility changed"));
    }

    @Test
    void detectsAddedAndRemovedCallables() {
        String base = """
                package com.example;

                public class Calculator {
                    public int oldMethod(int value) {
                        return value;
                    }
                }
                """;
        String head = """
                package com.example;

                public class Calculator {
                    public int newMethod(int value) {
                        return value;
                    }
                }
                """;

        JavaDiffResult result = analyzer.diff("Calculator.java", base, head);

        assertThat(result.changes()).hasSize(2);
        assertThat(result.changes()).extracting(MethodChange::kind)
                .containsExactlyInAnyOrder(MethodChangeKind.ADDED, MethodChangeKind.REMOVED);
    }

    @Test
    void detectsConstructorBodyChanges() {
        String base = """
                package com.example;

                public class Calculator {
                    private int value;

                    public Calculator() {
                        this.value = 1;
                    }
                }
                """;
        String head = """
                package com.example;

                public class Calculator {
                    private int value;

                    public Calculator() {
                        this.value = 2;
                    }
                }
                """;

        JavaDiffResult result = analyzer.diff("Calculator.java", base, head);

        assertThat(result.changes()).hasSize(1);
        MethodChange change = result.changes().get(0);
        assertThat(change.methodName()).isEqualTo("<init>");
        assertThat(change.kind()).isEqualTo(MethodChangeKind.MODIFIED);
    }

    @Test
    void ignoresFormattingAndComments() {
        String base = """
                package com.example;
                public class Calculator { public int add(int a,int b){return a+b;} }
                """;
        String head = """
                package com.example;

                public class Calculator {
                    // This comment should not count as a change.
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        JavaDiffResult result = analyzer.diff("Calculator.java", base, head);

        assertThat(result.changes()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void escapesPackagePrivateTypesFromPublicApi() {
        String base = """
                package com.example;

                class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        String head = """
                package com.example;

                class Calculator {
                    public int add(int a, int b) {
                        return a + b + 1;
                    }
                }
                """;

        JavaDiffResult result = analyzer.diff("Calculator.java", base, head);

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).publicApi()).isFalse();
    }

    @Test
    void skipsFilesThatCannotBeParsedOnEitherSide() {
        String base = "package com.example; class Calculator { void add() {} }";
        String head = "package com.example; class Calculator { void add( { }";

        JavaDiffResult result = analyzer.diff("Calculator.java", base, head);

        assertThat(result.changes()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("file skipped");
    }
}
