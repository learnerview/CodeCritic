package com.codecritic.analysis.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs SpotBugs against a temporary compilation of the submitted source.
 *
 * <p>SpotBugs performs static analysis on compiled Java bytecode rather than
 * directly analyzing the source code. Therefore, the submitted source is first
 * written to a temporary {@code .java} file and compiled into {@code .class}
 * files before being passed to SpotBugs.</p>
 *
 * <p>SpotBugs can detect potential issues such as:</p>
 * <ul>
 *     <li>Possible null pointer dereferences</li>
 *     <li>Dead stores (values assigned but never used)</li>
 *     <li>Resource leaks</li>
 *     <li>Incorrect implementations of {@code equals()} and {@code hashCode()}</li>
 *     <li>Suspicious object comparisons using {@code ==}</li>
 *     <li>Potential synchronization and concurrency issues</li>
 * </ul>
 *
 * <p>Example flow:</p>
 *
 * <pre>
 * Submitted Java source
 *         ↓
 * ClassUnderTest.java
 *         ↓
 * JavaCompiler (javac)
 *         ↓
 * ClassUnderTest.class
 *         ↓
 * SpotBugs static analysis
 *         ↓
 * Raw text findings returned as List&lt;String&gt;
 * </pre>
 *
 * <p>Compilation or SpotBugs failures are swallowed and reported as empty
 * results, keeping the analysis API resilient when the Java compiler or
 * SpotBugs executable is not installed.</p>
 */
@Component
public class SpotBugsRunner {

    private static final Logger log = LoggerFactory.getLogger(SpotBugsRunner.class);

    /**
     * Thrown when the submitted code cannot be compiled. SpotBugs needs compiled
     * bytecode, so it cannot run; the caller should surface this to the user
     * instead of quietly reporting "no bugs found".
     */
    public static class CompilationException extends RuntimeException {
        private final String compilerOutput;

        public CompilationException(String compilerOutput) {
            super("code did not compile successfully");
            this.compilerOutput = compilerOutput;
        }

        public String getCompilerOutput() {
            return compilerOutput;
        }
    }

    public List<String> run(String code) throws Exception {
        String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);
        Path tmpDir = Files.createTempDirectory("codecritic-" + requestId);
        log.debug("Created unique temp directory for SpotBugs analysis: {}", tmpDir);
        try {
            Path srcDir = Files.createDirectories(tmpDir.resolve("src"));
            // Name the temp file after the public top-level type so javac accepts
            // arbitrary submitted code. Java requires a public class/interface/enum
            // to live in a file named exactly after it; hardcoding "ClassUnderTest"
            // made compilation fail for any differently-named public class, which
            // silently produced zero SpotBugs findings.
            String className = resolveTopLevelTypeName(code);
            Path javaFile = srcDir.resolve(className + ".java");
            Files.writeString(javaFile, code);

            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return List.of();
            }

            Path classesDir = Files.createDirectories(tmpDir.resolve("classes"));
            javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics =
                    new javax.tools.DiagnosticCollector<>();
            try (javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
                if (fileManager == null) {
                    return List.of();
                }
                Iterable<? extends javax.tools.JavaFileObject> compilationUnits =
                        fileManager.getJavaFileObjectsFromFiles(List.of(javaFile.toFile()));
                javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fileManager, diagnostics, List.of("-d", classesDir.toString()), null, compilationUnits);
                if (!task.call()) {
                    StringBuilder out = new StringBuilder();
                    for (javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> d : diagnostics.getDiagnostics()) {
                        out.append(d.getKind()).append(": ")
                                .append(getLine(d)).append(": ")
                                .append(d.getMessage(null)).append('\n');
                    }
                    String compilerOutput = out.toString().trim();
                    log.warn("Code failed to compile; SpotBugs cannot run. Diagnostics: {}", compilerOutput);
                    throw new CompilationException(compilerOutput);
                }
            }

            ProcessBuilder pb = new ProcessBuilder("/usr/local/bin/spotbugs", "-textui", classesDir.toString());
            pb.redirectErrorStream(true);
            // Constrain the SpotBugs subprocess JVM so its transient heap (spawned
            // alongside this server's own JVM) does not blow past small-memory hosts
            // like the Render free tier (512 MB RAM). SpotBugs spawns its own JVM,
            // which would otherwise roughly double memory during analysis.
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Xmx128m -Xms16m");
            Process process;
            try {
                log.info("Starting SpotBugs subprocess for directory: {}", classesDir);
                process = pb.start();
            } catch (Exception ex) {
                log.error("Failed to start SpotBugs process: {}", ex.getMessage());
                return List.of();
            }

            List<String> results = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Skip launcher/header noise: the -textui header row, blank lines,
                    // and the JVM's "Picked up JAVA_TOOL_OPTIONS" notice (which is printed
                    // to stderr, merged here — it is NOT a SpotBugs finding).
                    if (line.isBlank()
                            || line.startsWith("category")
                            || line.startsWith("Picked up JAVA_TOOL_OPTIONS")) {
                        continue;
                    }
                    results.add(line);
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                log.warn("SpotBugs process timed out after 60s; destroying process.");
                process.destroyForcibly();
            }
            log.info("SpotBugs finished, found {} findings", results.size());
            return results;
        } finally {
            deleteDirectory(tmpDir);
        }
    }

    private void deleteDirectory(Path path) {
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        } catch (Exception ignored) {
        }
    }

    private String getLine(javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> d) {
        return d.getLineNumber() >= 0 ? "line " + d.getLineNumber() : "?";
    }

    /**
     * Finds the name of the public top-level type declared in the submitted
     * source so the temp file can be named correctly. Falls back to
     * {@code ClassUnderTest} when no public type declaration is found.
     */
    private String resolveTopLevelTypeName(String code) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\bpublic\\s+(?:abstract\\s+|final\\s+|strictfp\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)")
                .matcher(code);
        if (m.find()) {
            return m.group(1);
        }
        return "ClassUnderTest";
    }
}
