package com.codecritic.analysis.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
 *
 * <p>The subprocess is bounded by {@link #DEFAULT_TIMEOUT_SECONDS}: the whole
 * run (including draining its output) is subject to that budget, and the
 * process is forcibly destroyed and awaited before its temp directory is
 * removed, so a hung or pathological submission cannot pin a request thread or
 * leak a process.</p>
 */
@Component
public class SpotBugsRunner {

    private static final Logger log = LoggerFactory.getLogger(SpotBugsRunner.class);

    /** Default SpotBugs CLI invocation; the classes dir is appended per run. */
    static final String SPOTBUGS_COMMAND = "/usr/local/bin/spotbugs";

    /** Wall-clock budget for the whole subprocess: start, run, drain output, exit. */
    static final long DEFAULT_TIMEOUT_SECONDS = 60L;

    /**
     * Upper bound on how long a {@code destroyForcibly()} is given to take effect.
     * Must be short: the temp directory holding the compiled classes cannot be
     * deleted safely while the process may still be writing into it.
     */
    private static final long TERMINATION_WAIT_SECONDS = 5L;

    /**
     * Upper bound on waiting for the output-reader thread once the process has
     * exited. Bounded on purpose: a grandchild may still hold the stdout pipe
     * open, and this thread must never be able to stall the caller.
     */
    private static final long READER_JOIN_MILLIS = 1_000L;

    /** Command prefix used to launch SpotBugs; the classes dir is appended to it. */
    private final List<String> command;

    /** Wall-clock budget for one SpotBugs run, in seconds. */
    private final long timeoutSeconds;

    public SpotBugsRunner() {
        this(List.of(SPOTBUGS_COMMAND, "-textui"), DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Test seam: lets a fake "spotbugs" binary be substituted so process
     * lifecycle behaviour (timeout, kill, output capture) can be exercised
     * without a real SpotBugs install.
     */
    SpotBugsRunner(List<String> command, long timeoutSeconds) {
        this.command = List.copyOf(command);
        this.timeoutSeconds = timeoutSeconds;
    }

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
        // Hoisted out of the try so the finally can guarantee the subprocess is
        // gone before the temp directory it writes into is deleted.
        Process process = null;
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

            List<String> launcher = new java.util.ArrayList<>(this.command);
            launcher.add(classesDir.toString());
            ProcessBuilder pb = new ProcessBuilder(launcher);
            pb.redirectErrorStream(true);
            // Constrain the SpotBugs subprocess JVM so its transient heap (spawned
            // alongside this server's own JVM) does not blow past small-memory hosts
            // like the Render free tier (512 MB RAM). SpotBugs spawns its own JVM,
            // which would otherwise roughly double memory during analysis.
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Xmx192m -Xms32m -XX:MaxMetaspaceSize=64m");
            try {
                log.info("Starting SpotBugs subprocess for directory: {}", classesDir);
                process = pb.start();
            } catch (Exception ex) {
                log.error("Failed to start SpotBugs process: {}", ex.getMessage());
                return List.of();
            }

            List<String> results = collectFindings(process);
            log.info("SpotBugs output collected, found {} findings", results.size());
            return results;
        } finally {
            // Must run before deleteDirectory: the subprocess writes .class/.aux
            // files into tmpDir, so deleting first could race a live process and
            // leave the tree behind (or hit a sharing violation on Windows).
            terminate(process);
            // Releasing the pipes unblocks the reader thread if it is still
            // parked in readLine() on a pipe some grandchild kept open.
            closeStreams(process);
            deleteDirectory(tmpDir);
        }
    }

    /**
     * Drains the subprocess output while bounding the whole run with the
     * timeout. Output is read on a separate thread because {@code readLine()}
     * blocks until EOF: if the process hangs without closing stdout, reading on
     * this thread would block forever and the timeout would never be reached.
     * Only the process itself is awaited here, so the timeout always fires.
     *
     * @return the findings read so far (possibly partial if the run timed out)
     */
    private List<String> collectFindings(Process process) throws InterruptedException {
        // Copy-on-write: the reader thread appends while this thread snapshots,
        // so the list must tolerate concurrent writes and safe iteration.
        List<String> results = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> readOutput(process, results), "spotbugs-output-reader");
        reader.setDaemon(true);
        reader.start();

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            log.warn("SpotBugs process did not exit within {}s; destroying process.", timeoutSeconds);
            // Kill first, join second: joining a reader that is still blocked on
            // a live process's stdout would reintroduce the unbounded wait.
            terminate(process);
        }
        reader.join(READER_JOIN_MILLIS);
        return List.copyOf(results);
    }

    /** Reader thread body; never propagates, a broken pipe is an expected outcome. */
    private void readOutput(Process process, List<String> results) {
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
        } catch (Exception ex) {
            log.debug("SpotBugs output ended early: {}", ex.toString());
        }
    }

    /**
     * Ensures the subprocess is dead. Idempotent, and a no-op for {@code null}
     * (the process never started) or for an already-exited process.
     */
    private void terminate(Process process) {
        if (process == null) {
            return;
        }
        try {
            if (process.isAlive()) {
                log.warn("Forcefully destroying SpotBugs process {}.", process.pid());
                process.destroyForcibly();
                // destroyForcibly() only signals; wait so the delete below cannot
                // race a still-running process writing into the temp directory.
                if (!process.waitFor(TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("SpotBugs process {} survived destroyForcibly; temp directory may not be fully removed.",
                            process.pid());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Releases the subprocess pipes so no reader thread stays parked on them. */
    private void closeStreams(Process process) {
        if (process == null) {
            return;
        }
        closeQuietly(process.getInputStream());
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getErrorStream());
    }

    private void closeQuietly(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
        }
    }

    private void deleteDirectory(Path path) {
        // Files.walk opens a directory handle that must be released before the
        // entries it yields can be deleted, hence try-with-resources.
        try (Stream<Path> tree = Files.walk(path)) {
            tree.sorted(Comparator.reverseOrder())
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
