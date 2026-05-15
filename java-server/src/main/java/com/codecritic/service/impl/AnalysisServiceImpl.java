package com.codecritic.service.impl;

import com.codecritic.dto.*;
import com.codecritic.service.AnalysisService;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementation of AnalysisService that uses JavaParser for structural analysis and
 * attempts a best-effort SpotBugs run for richer bug detection.
 */
@Service
public class AnalysisServiceImpl implements AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisServiceImpl.class);

    @Override
    public ComplexityResponse calculateComplexity(String code) {
        if (code == null || code.isBlank()) {
            return new ComplexityResponse(0, 0);
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            ComplexityVisitor visitor = new ComplexityVisitor();
            visitor.visit(cu, null);
            int maxCyclomatic = visitor.getMaxCyclomatic();
            int cognitive = Math.max(1, maxCyclomatic / 2);
            return new ComplexityResponse(maxCyclomatic, cognitive);
        } catch (Exception e) {
            log.warn("JavaParser failed to parse source; falling back to heuristic: {}", e.getMessage());
            return fallbackComplexityHeuristic(code);
        }
    }

    private ComplexityResponse fallbackComplexityHeuristic(String code) {
        int cyclomatic = 1;
        String[] tokens = {"if(", "for(", "while(", "case ", "&&", "||", "catch("};
        for (String t : tokens) {
            int idx = 0;
            while ((idx = code.indexOf(t, idx)) != -1) {
                cyclomatic++;
                idx += t.length();
            }
        }
        int cognitive = Math.max(1, cyclomatic / 2);
        return new ComplexityResponse(cyclomatic, cognitive);
    }

    private static class ComplexityVisitor extends VoidVisitorAdapter<Void> {
        private int current = 0;
        private int max = 0;

        @Override
        public void visit(MethodDeclaration md, Void arg) {
            int before = current;
            current = 1; // base
            super.visit(md, arg);
            if (current > max) max = current;
            current = before;
        }

        @Override
        public void visit(IfStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ForStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ForEachStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(WhileStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(DoStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(SwitchEntry n, Void arg) {
            if (n.getLabels() != null && !n.getLabels().isEmpty()) {
                current += n.getLabels().size();
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(CatchClause n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ConditionalExpr n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(BinaryExpr n, Void arg) {
            if (n.getOperator() == BinaryExpr.Operator.AND || n.getOperator() == BinaryExpr.Operator.OR) {
                current++;
            }
            super.visit(n, arg);
        }

        public int getMaxCyclomatic() {
            return Math.max(1, max);
        }
    }

    @Override
    public BugReport findBugs(String code) {
        List<BugFinding> bugs = new ArrayList<>();
        if (code == null || code.isBlank()) return new BugReport(bugs);

        String[] lines = code.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.contains("/") && l.contains("0") && l.matches(".*\\/\\s*0.*")) {
                bugs.add(new BugFinding("DivisionByZeroRisk", i + 1, "Possible division by zero", "Check divisor for zero"));
            }
            if (l.contains(".toString()") && !l.contains("!= null") && !l.contains("Objects.toString")) {
                bugs.add(new BugFinding("NullPointerRisk", i + 1, "Calling toString() might NPE if obj is null", "Add null check or use String.valueOf()"));
            }
        }

        try {
            List<BugFinding> spot = runSpotBugsOnCode(code);
            if (spot != null) bugs.addAll(spot);
        } catch (Exception e) {
            log.info("SpotBugs analysis skipped or failed: {}", e.getMessage());
        }

        return new BugReport(bugs);
    }

    private List<BugFinding> runSpotBugsOnCode(String code) throws Exception {
        String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);
        Path tmpDir = Files.createTempDirectory("codecritic-" + requestId);
        log.debug("Created unique temp directory for analysis: {}", tmpDir);
        try {
            Path srcDir = Files.createDirectories(tmpDir.resolve("src"));
            Path javaFile = srcDir.resolve("ClassUnderTest.java");
            Files.writeString(javaFile, code);

            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return List.of();

            Path classesDir = Files.createDirectories(tmpDir.resolve("classes"));
            try (javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
                if (fileManager == null) return List.of();
                
                Iterable<? extends javax.tools.JavaFileObject> compilationUnits = 
                    fileManager.getJavaFileObjectsFromFiles(List.of(javaFile.toFile()));
                
                javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, null, List.of("-d", classesDir.toString()), null, compilationUnits);
                
                if (!task.call()) return List.of();
            }

            ProcessBuilder pb = new ProcessBuilder("spotbugs", "-textui", classesDir.toString());
            pb.redirectErrorStream(true);
            Process p;
            try {
                log.info("Starting SpotBugs subprocess for directory: {}", classesDir);
                p = pb.start();
            } catch (Exception ex) {
                log.error("Failed to start SpotBugs process: {}", ex.getMessage());
                return List.of();
            }

            List<BugFinding> results = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    results.add(new BugFinding("SpotBugsFinding", 0, line, "See SpotBugs output"));
                }
            }
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("SpotBugs process timed out after 60s; destroying process.");
                p.destroyForcibly();
            }
            return results;
        } finally {
            log.debug("Cleaning up analysis directory: {}", tmpDir);
            deleteDirectory(tmpDir);
        }
    }

    private void deleteDirectory(Path path) {
        try {
            Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
        } catch (Exception ignored) {}
    }

    @Override
    public TestGenerationResponse generateTest(String className, String methodName, String parameters, String code) {
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
                        final String searchName = finalMethodName;
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
            String[] params = parameters.split(",");
            argumentLiterals = java.util.Arrays.stream(params)
                    .map(String::trim)
                    .map(this::exampleLiteralForType)
                    .collect(Collectors.joining(", "));
        }

        if (finalMethodName == null || finalMethodName.isBlank()) finalMethodName = "methodUnderTest";
        if (finalClassName == null || finalClassName.isBlank()) finalClassName = "ClassUnderTest";

        String testName = "test" + Character.toUpperCase(finalMethodName.charAt(0)) + finalMethodName.substring(1);
        String invocation = "obj." + finalMethodName + "(" + (argumentLiterals == null ? "" : argumentLiterals) + ")";

        String assertionBlock = "void".equals(returnType) ?
                "        assertDoesNotThrow(() -> " + invocation + ");\n" :
                "        var result = assertDoesNotThrow(() -> " + invocation + ");\n" + buildResultAssertion(returnType);

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
        
        return switch (type) {
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
