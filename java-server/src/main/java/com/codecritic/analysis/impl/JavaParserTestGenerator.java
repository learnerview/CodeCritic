package com.codecritic.analysis.impl;

import com.codecritic.analysis.TestGenerator;
import com.codecritic.dto.TestGenerationResponse;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Deterministic JUnit 5 scaffold generator.
 *
 * Parses the provided source with JavaParser to extract real class/method
 * signatures and parameter types; falls back to caller-supplied metadata when
 * parsing is not possible.
 */
@Component
public class JavaParserTestGenerator implements TestGenerator {

    private static final Logger log = LoggerFactory.getLogger(JavaParserTestGenerator.class);

    @Override
    public TestGenerationResponse generate(String className, String methodName, String parameters, String code) {
        String finalClassName = className;
        String finalMethodName = methodName;
        String packageLine = "";
        String argumentLiterals = "";
        String returnType = "void";

        if (code != null && !code.isBlank()) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(code);
                packageLine = cu.getPackageDeclaration()
                        .map(p -> "package " + p.getNameAsString() + ";\n\n")
                        .orElse("");
                Optional<ClassOrInterfaceDeclaration> cls = cu.findFirst(ClassOrInterfaceDeclaration.class);
                if (cls.isPresent()) {
                    ClassOrInterfaceDeclaration c = cls.get();
                    if (finalClassName == null || finalClassName.isBlank()) {
                        finalClassName = c.getNameAsString();
                    }

                    MethodDeclaration target = null;
                    if (finalMethodName != null && !finalMethodName.isBlank()) {
                        String searchName = finalMethodName;
                        target = c.getMethods().stream()
                                .filter(m -> m.getNameAsString().equals(searchName))
                                .findFirst()
                                .orElse(null);
                    }
                    if (target == null && !c.getMethods().isEmpty()) {
                        target = c.getMethods().get(0);
                        finalMethodName = target.getNameAsString();
                    }

                    if (target != null) {
                        returnType = target.getType().asString();
                        argumentLiterals = target.getParameters().stream()
                                .map(this::exampleLiteralForParameter)
                                .collect(Collectors.joining(", "));
                    }
                }
            } catch (Exception e) {
                log.debug("JavaParser failed in test generation context: {}", e.getMessage());
            }
        }

        if ((argumentLiterals == null || argumentLiterals.isBlank()) && parameters != null && !parameters.isBlank()) {
            argumentLiterals = Arrays.stream(parameters.split(","))
                    .map(String::trim)
                    .map(this::exampleLiteralForType)
                    .collect(Collectors.joining(", "));
        }

        if (finalMethodName == null || finalMethodName.isBlank()) finalMethodName = "methodUnderTest";
        if (finalClassName == null || finalClassName.isBlank()) finalClassName = "ClassUnderTest";

        String testName = "test" + Character.toUpperCase(finalMethodName.charAt(0)) + finalMethodName.substring(1);
        String invocation = "obj." + finalMethodName + "(" + (argumentLiterals == null ? "" : argumentLiterals) + ")";

        String assertionBlock = "void".equals(returnType)
                ? "        assertDoesNotThrow(() -> " + invocation + ");\n"
                : "        var result = assertDoesNotThrow(() -> " + invocation + ");\n" + buildResultAssertion(returnType);

        String junit = packageLine +
                "import org.junit.jupiter.api.Test;\n" +
                "import static org.junit.jupiter.api.Assertions.*;\n\n" +
                "class " + finalClassName + "Test {\n\n" +
                "    @Test\n" +
                "    void " + testName + "() {\n" +
                "        " + finalClassName + " obj = new " + finalClassName + "();\n" +
                assertionBlock +
                "    }\n" +
                "}\n";
        return new TestGenerationResponse(junit);
    }

    private String exampleLiteralForParameter(Parameter parameter) {
        return exampleLiteralForType(parameter.getType().asString());
    }

    private String exampleLiteralForType(String typeValue) {
        String type = typeValue == null ? "" : typeValue.trim();
        if (type.isEmpty()) return "null";
        String simple = type.substring(type.lastIndexOf('.') + 1);
        return switch (simple) {
            case "byte", "short", "int", "long", "Byte", "Short", "Integer", "Long" -> "1";
            case "float", "double", "Float", "Double" -> "1.0";
            case "boolean", "Boolean" -> "true";
            case "char", "Character" -> "'a'";
            case "String" -> "\"sample\"";
            default -> "null";
        };
    }

    private String buildResultAssertion(String returnType) {
        String type = returnType == null ? "" : returnType.trim();
        if (type.equals("boolean") || type.equals("Boolean")) {
            return "        assertTrue(result == Boolean.TRUE || result == Boolean.FALSE);\n";
        }
        return "        assertNotNull(result);\n";
    }
}
