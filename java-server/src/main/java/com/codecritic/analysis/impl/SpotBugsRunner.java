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

    public List<String> run(String code) throws Exception {
        String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);
        Path tmpDir = Files.createTempDirectory("codecritic-" + requestId);
        log.debug("Created unique temp directory for SpotBugs analysis: {}", tmpDir);
        try {
            Path srcDir = Files.createDirectories(tmpDir.resolve("src"));
            Path javaFile = srcDir.resolve("ClassUnderTest.java");
            Files.writeString(javaFile, code);

            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return List.of();
            }

            Path classesDir = Files.createDirectories(tmpDir.resolve("classes"));
            try (javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
                if (fileManager == null) {
                    return List.of();
                }
                Iterable<? extends javax.tools.JavaFileObject> compilationUnits =
                        fileManager.getJavaFileObjectsFromFiles(List.of(javaFile.toFile()));
                javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fileManager, null, List.of("-d", classesDir.toString()), null, compilationUnits);
                if (!task.call()) {
                    return List.of();
                }
            }

            ProcessBuilder pb = new ProcessBuilder("spotbugs", "-textui", classesDir.toString());

            // Merges stderr into stdout so both normal output and errors can be read
            // from process.getInputStream() using a single BufferedReader.
            pb.redirectErrorStream(true);
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
                    if (!line.isBlank()) {
                        results.add(line);
                    }
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                log.warn("SpotBugs process timed out after 60s; destroying process.");
                process.destroyForcibly();
            }
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
}
