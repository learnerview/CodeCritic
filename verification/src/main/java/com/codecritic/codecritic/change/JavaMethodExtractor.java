package com.codecritic.change;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class JavaMethodExtractor {

    private JavaMethodExtractor() {
    }

    public static Map<String, MethodInfo> extract(CompilationUnit unit) {
        Map<String, MethodInfo> methods = new LinkedHashMap<>();
        unit.findAll(MethodDeclaration.class).forEach(method -> {
            MethodInfo info = describe(method, false);
            methods.put(info.key(), info);
        });
        unit.findAll(ConstructorDeclaration.class).forEach(constructor -> {
            MethodInfo info = describe(constructor, true);
            methods.put(info.key(), info);
        });
        return methods;
    }

    public static String key(CallableDeclaration<?> callable) {
        String name = callable instanceof ConstructorDeclaration ? "<init>" : callable.getNameAsString();
        return className(callable) + "#" + name + "(" + parameterTypes(callable) + ")";
    }

    public static String className(CallableDeclaration<?> callable) {
        List<String> names = new ArrayList<>();
        Node current = callable.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                names.add(type.getNameAsString());
            }
            current = current.getParentNode().orElse(null);
        }
        Collections.reverse(names);
        String packageName = callable.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(declaration -> declaration.getNameAsString())
                .orElse("");
        String localName = String.join(".", names);
        return packageName.isBlank() ? localName : packageName + "." + localName;
    }

    public static String simpleClassName(CallableDeclaration<?> callable) {
        Node current = callable.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                return type.getNameAsString();
            }
            current = current.getParentNode().orElse(null);
        }
        return "";
    }

    public static boolean isPublicApi(CallableDeclaration<?> callable) {
        boolean visible = callable.isPublic() || callable.isProtected();
        if (!visible) {
            TypeDeclaration<?> nearestType = null;
            Node current = callable.getParentNode().orElse(null);
            while (current != null) {
                if (current instanceof TypeDeclaration<?> type) {
                    nearestType = type;
                    break;
                }
                current = current.getParentNode().orElse(null);
            }
            if (nearestType instanceof ClassOrInterfaceDeclaration type
                    && type.isInterface()
                    && !callable.isPrivate()) {
                visible = true;
            }
        }
        if (!visible) {
            return false;
        }

        Node current = callable.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type && !type.isPublic()) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return true;
    }

    public static String signature(CallableDeclaration<?> callable) {
        List<String> parts = new ArrayList<>();
        String modifiers = callable.getModifiers().stream()
                .map(modifier -> modifier.getKeyword().asString())
                .sorted()
                .collect(Collectors.joining(" "));
        if (!modifiers.isBlank()) {
            parts.add(modifiers);
        }
        if (!callable.getTypeParameters().isEmpty()) {
            parts.add("<" + callable.getTypeParameters().stream()
                    .map(Node::toString)
                    .collect(Collectors.joining(", ")) + ">");
        }
        if (callable instanceof MethodDeclaration method) {
            parts.add(method.getTypeAsString());
        }
        parts.add(callable.getNameAsString() + "(" + parameterTypes(callable) + ")");
        if (!callable.getThrownExceptions().isEmpty()) {
            parts.add("throws " + callable.getThrownExceptions().stream()
                    .map(Node::toString)
                    .collect(Collectors.joining(", ")));
        }
        return String.join(" ", parts);
    }

    public static String parameterTypes(CallableDeclaration<?> callable) {
        return callable.getParameters().stream()
                .map(JavaMethodExtractor::parameterType)
                .collect(Collectors.joining(", "));
    }

    private static String parameterType(Parameter parameter) {
        String type = parameter.getTypeAsString();
        return parameter.isVarArgs() ? type + "..." : type;
    }

    public static String body(CallableDeclaration<?> callable) {
        if (callable instanceof MethodDeclaration method) {
            return method.getBody().map(Node::toString).orElse("");
        }
        if (callable instanceof ConstructorDeclaration constructor) {
            return constructor.getBody().toString();
        }
        return "";
    }

    public static int line(CallableDeclaration<?> callable) {
        return callable.getBegin().map(position -> position.line).orElse(0);
    }

    private static MethodInfo describe(CallableDeclaration<?> callable, boolean constructor) {
        String methodName = constructor ? "<init>" : callable.getNameAsString();
        return new MethodInfo(
                key(callable),
                className(callable),
                methodName,
                signature(callable),
                body(callable),
                line(callable),
                isPublicApi(callable),
                CyclomaticComplexity.of(callable));
    }
}
