package com.codecritic.change;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ProjectIndex {

    private final Map<String, String> sources;
    private final Map<String, List<Caller>> callersByMethodName;

    private ProjectIndex(Map<String, String> sources, Map<String, List<Caller>> callersByMethodName) {
        this.sources = Map.copyOf(sources);
        this.callersByMethodName = Map.copyOf(callersByMethodName);
    }

    public static ProjectIndex from(Map<String, String> sources) {
        Map<String, List<Caller>> callers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String path = entry.getKey();
            boolean test = isTestPath(path);
            JavaSourceParser.parse(entry.getValue()).ifPresent(unit ->
                    indexUnit(unit, path, test, callers));
        }
        return new ProjectIndex(sources, callers);
    }

    public int productionCallerCount(String changedKey, String methodName) {
        return (int) callersByMethodName.getOrDefault(methodName, List.of()).stream()
                .filter(caller -> !caller.test())
                .filter(caller -> !caller.callerKey().equals(changedKey))
                .map(Caller::callerKey)
                .distinct()
                .count();
    }

    public List<String> affectedTests(String className, String methodName) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        Set<String> tests = new TreeSet<>();

        callersByMethodName.getOrDefault(methodName, List.of()).stream()
                .filter(Caller::test)
                .map(Caller::path)
                .forEach(tests::add);

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            if (!isTestPath(entry.getKey())) {
                continue;
            }
            String source = entry.getValue();
            if (source.contains(simpleName) || source.contains(methodName)) {
                tests.add(entry.getKey());
            }
        }
        return new ArrayList<>(tests);
    }

    public Map<String, String> sources() {
        return sources;
    }

    private static void indexUnit(
            CompilationUnit unit,
            String path,
            boolean test,
            Map<String, List<Caller>> callers) {
        unit.findAll(MethodDeclaration.class).forEach(method ->
                indexCallable(method, path, test, callers));
        unit.findAll(ConstructorDeclaration.class).forEach(constructor ->
                indexCallable(constructor, path, test, callers));
    }

    private static void indexCallable(
            CallableDeclaration<?> callable,
            String path,
            boolean test,
            Map<String, List<Caller>> callers) {
        String callerKey = JavaMethodExtractor.key(callable);

        callable.findAll(MethodCallExpr.class).stream()
                .filter(call -> CallableScope.belongsTo(call, callable))
                .forEach(call -> addCaller(
                        callers,
                        call.getNameAsString(),
                        new Caller(path, callerKey, test, line(call))));

        callable.findAll(MethodReferenceExpr.class).stream()
                .filter(reference -> CallableScope.belongsTo(reference, callable))
                .forEach(reference -> addCaller(
                        callers,
                        reference.getIdentifier(),
                        new Caller(path, callerKey, test, line(reference))));
    }

    private static void addCaller(Map<String, List<Caller>> callers, String methodName, Caller caller) {
        callers.computeIfAbsent(methodName, ignored -> new ArrayList<>()).add(caller);
    }

    private static int line(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }

    public static boolean isTestPath(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.contains("/src/test/java/")
                || normalized.endsWith("Test.java")
                || normalized.endsWith("Tests.java")
                || normalized.endsWith("IT.java");
    }

    private record Caller(String path, String callerKey, boolean test, int line) {
    }
}
