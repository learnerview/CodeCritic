package com.codecritic.analysis.impl;

import com.codecritic.dto.TestGenerationResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaParserTestGeneratorTest {

    private final JavaParserTestGenerator generator = new JavaParserTestGenerator();

    @Test
    void generate_extractsClassNameAndMethod() {
        String code = "public class MyClass { public int add(int a, boolean b){ return a; } }";
        TestGenerationResponse resp = generator.generate("", "add", "", code);
        assertTrue(resp.junitCode().contains("MyClassTest"));
        assertTrue(resp.junitCode().contains("obj.add(1, true)"));
        assertTrue(resp.junitCode().contains("@Test"));
    }

    @Test
    void generate_includesPackageDeclaration() {
        String code = "package com.example; public class Calc { public int f(){ return 1; } }";
        TestGenerationResponse resp = generator.generate("", "", "", code);
        assertTrue(resp.junitCode().contains("package com.example;"));
    }

    @Test
    void generate_voidMethod_usesDoesNotThrow() {
        String code = "public class Runner { public void run(){ } }";
        TestGenerationResponse resp = generator.generate("", "run", "", code);
        assertTrue(resp.junitCode().contains("assertDoesNotThrow"));
    }

    @Test
    void generate_noCode_usesProvidedMetadata() {
        TestGenerationResponse resp = generator.generate("Calc", "sum", "int, int", "");
        assertTrue(resp.junitCode().contains("CalcTest"));
        assertTrue(resp.junitCode().contains("obj.sum(1, 1)"));
    }

    @Test
    void generate_stringParameter_usesStringLiteral() {
        String code = "public class Greeter { public String greet(String name){ return name; } }";
        TestGenerationResponse resp = generator.generate("", "greet", "", code);
        assertTrue(resp.junitCode().contains("obj.greet(\"sample\")"));
    }
}
