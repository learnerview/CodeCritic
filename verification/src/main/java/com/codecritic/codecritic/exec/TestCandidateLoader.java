package com.codecritic.exec;

import com.codecritic.change.JavaSourceParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TestCandidateLoader {

    private TestCandidateLoader() {
    }

    public static TestCandidate load(Path sourceFile, Path repository, String requestedPath) {
        Path source = sourceFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Test source does not exist: " + source);
        }

        String content;
        try {
            content = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read test source: " + e.getMessage(), e);
        }

        CompilationUnit unit = JavaSourceParser.parse(content)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Test source is not a parseable Java compilation unit: " + source));
        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");
        String className = unit.getTypes().stream()
                .findFirst()
                .map(TypeDeclaration::getNameAsString)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Test source must declare a top-level class: " + source));

        String relativePath = requestedPath == null || requestedPath.isBlank()
                ? inferredPath(source, repository, packageName, className)
                : requestedPath;

        Path relative = Path.of(relativePath.replace('\\', '/')).normalize();
        if (relative.isAbsolute()
                || relative.startsWith("..")
                || !relative.toString().endsWith(".java")) {
            throw new IllegalArgumentException(
                    "Test path must be a relative .java path inside the project: " + relativePath);
        }

        String selector = packageName.isBlank() ? className : packageName + "." + className;
        return new TestCandidate(
                content,
                relative.toString().replace('\\', '/'),
                selector);
    }

    private static String inferredPath(
            Path source,
            Path repository,
            String packageName,
            String className) {
        Path root = repository.toAbsolutePath().normalize();
        try {
            Path relative = root.relativize(source).normalize();
            if (relative.startsWith("src/test/java/")) {
                return relative.toString().replace('\\', '/');
            }
        } catch (IllegalArgumentException ignored) {
            // The source is on another filesystem root; use the package layout.
        }

        String packagePath = packageName.isBlank()
                ? ""
                : packageName.replace('.', '/') + "/";
        return "src/test/java/" + packagePath + className + ".java";
    }
}
