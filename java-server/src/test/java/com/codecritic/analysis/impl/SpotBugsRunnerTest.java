package com.codecritic.analysis.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SpotBugsRunnerTest {

    /** Compiles cleanly, so the runner gets as far as launching the subprocess. */
    private static final String COMPILABLE_CODE = """
            public class Ok {
                private int value;
                void bump(int a) {
                    if (a > 0) {
                        value = a;
                    }
                }
            }
            """;

    private static final long RUN_DEADLINE_MS = 30_000L;

    @TempDir
    Path testDir;

    private Path reportFile;

    @BeforeEach
    void setUp() {
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "tests need a JDK, not a JRE");
        assumeTrue(Files.isExecutable(javaExecutable()), "java executable not found under " + System.getProperty("java.home"));
        reportFile = testDir.resolve("fake-spotbugs-report.txt");
    }

    /**
     * DEFECT 1: a process that never exits and never closes stdout must not
     * pin the calling thread. Before the fix, run() drained stdout with
     * readLine() before reaching waitFor(60, SECONDS), so it blocked forever and
     * the timeout was unreachable.
     */
    @Test
    void processThatNeverExitsOrClosesStdout_returnsWithinTheTimeout() throws Exception {
        SpotBugsRunner runner = new SpotBugsRunner(hangingCommand(), 2L);

        long startedAt = System.nanoTime();
        List<String> findings = runWithDeadline(runner, COMPILABLE_CODE, RUN_DEADLINE_MS);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(elapsedMs >= 1_000L,
                "run() returned after only " + elapsedMs + "ms; the timeout budget was never applied");
        assertTrue(elapsedMs < RUN_DEADLINE_MS,
                "run() took " + elapsedMs + "ms, which is not bounded by the timeout");
        // The reader thread runs concurrently with the timeout wait, so output
        // produced before the hang is still returned.
        assertTrue(findings.stream().anyMatch(line -> line.contains("partial finding printed before the hang")),
                "findings read before the timeout were lost: " + findings);
    }

    /** DEFECT 2: the timed-out subprocess must not survive the call. */
    @Test
    void timedOutProcess_isDestroyedAndTempDirectoryRemoved() throws Exception {
        SpotBugsRunner runner = new SpotBugsRunner(hangingCommand(), 2L);

        runWithDeadline(runner, COMPILABLE_CODE, RUN_DEADLINE_MS);

        long pid = reportedPid();
        assertTrue(awaitDead(pid, 10_000L),
                "SpotBugs subprocess " + pid + " is still running after run() returned");
        assertFalse(Files.exists(reportedClassesDir()),
                "temp classes directory survived the run: " + reportedClassesDir());
        assertFalse(Files.exists(reportedClassesDir().getParent()),
                "temp directory survived the run: " + reportedClassesDir().getParent());
    }

    /**
     * DEFECT 2, second half: when the process exits on its own it must still be
     * cleaned up, and the runner must not report a leaked handle or throw.
     */
    @Test
    void cleanExit_collectsFindingsAndLeavesNoRunningProcess() throws Exception {
        SpotBugsRunner runner = new SpotBugsRunner(exitingCommand(), 30L);

        List<String> findings = runWithDeadline(runner, COMPILABLE_CODE, RUN_DEADLINE_MS);

        assertEquals(2, findings.size(), "unexpected findings: " + findings);
        assertTrue(findings.get(0).contains("NP_ALWAYS_NULL"), "unexpected first finding: " + findings);
        assertTrue(findings.get(1).contains("DE_MIGHT_IGNORE"), "unexpected second finding: " + findings);
        assertFalse(findings.stream().anyMatch(line -> line.startsWith("category")),
                "header noise leaked into the findings: " + findings);
        assertFalse(findings.stream().anyMatch(line -> line.startsWith("Picked up JAVA_TOOL_OPTIONS")),
                "JVM option notice leaked into the findings: " + findings);
        assertTrue(awaitDead(reportedPid(), 10_000L), "process still running after a clean exit");
        assertFalse(Files.exists(reportedClassesDir().getParent()), "temp directory survived the run");
    }

    /**
     * DEFECT 3: the walk stream used to delete the temp tree was never closed.
     * The observable consequence is leftover "codecritic-*" temp directories, so
     * assert none accumulate across many runs.
     */
    @Test
    void repeatedRuns_leaveNoTempDirectoriesBehind() throws Exception {
        Set<String> before = codecriticTempDirs();
        SpotBugsRunner runner = new SpotBugsRunner(exitingCommand(), 30L);

        for (int i = 0; i < 20; i++) {
            runWithDeadline(runner, COMPILABLE_CODE, RUN_DEADLINE_MS);
        }

        assertEquals(before, codecriticTempDirs(), "temp directories leaked from SpotBugs runs");
    }

    /** Same guarantee for the path where compilation fails and no process starts. */
    @Test
    void compilationFailure_leavesNoTempDirectoryBehind() throws Exception {
        Set<String> before = codecriticTempDirs();
        SpotBugsRunner runner = new SpotBugsRunner(hangingCommand(), 2L);

        for (int i = 0; i < 10; i++) {
            assertThrowsCompilationFailure(runner);
        }

        assertEquals(before, codecriticTempDirs(), "temp directories leaked from failed compilations");
    }

    /** A missing executable must stay a soft failure, as before. */
    @Test
    void missingExecutable_returnsEmptyAndCleansUp() throws Exception {
        SpotBugsRunner runner = new SpotBugsRunner(
                List.of(testDir.resolve("definitely-not-here").toString(), "-textui"), 5L);

        Set<String> before = codecriticTempDirs();
        List<String> findings = runWithDeadline(runner, COMPILABLE_CODE, RUN_DEADLINE_MS);

        assertTrue(findings.isEmpty(), "expected no findings when SpotBugs cannot start: " + findings);
        assertEquals(before, codecriticTempDirs(), "temp directory survived a failed process start");
    }

    /** Pins the documented default budget; the timeout is not configurable today. */
    @Test
    void defaultTimeoutIsSixtySeconds() {
        assertEquals(60L, SpotBugsRunner.DEFAULT_TIMEOUT_SECONDS);
        assertEquals("/usr/local/bin/spotbugs", SpotBugsRunner.SPOTBUGS_COMMAND);
    }

    private void assertThrowsCompilationFailure(SpotBugsRunner runner) {
        try {
            runner.run("public class Broken { this is not java }");
            fail("expected CompilationException");
        } catch (SpotBugsRunner.CompilationException expected) {
            assertFalse(expected.getCompilerOutput().isBlank());
        } catch (Exception e) {
            fail("expected CompilationException but got " + e);
        }
    }

    private List<String> hangingCommand() throws Exception {
        return fakeSpotBugsCommand("hang");
    }

    private List<String> exitingCommand() throws Exception {
        return fakeSpotBugsCommand("exit");
    }

    private List<String> fakeSpotBugsCommand(String mode) throws Exception {
        return List.of(javaExecutable().toString(), "-cp", testClasspath(),
                SpotBugsRunnerFakeProcess.class.getName(), reportFile.toString(), mode, "-textui");
    }

    private static Path javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase().startsWith("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe);
    }

    private static String testClasspath() throws Exception {
        URL location = SpotBugsRunnerFakeProcess.class.getProtectionDomain().getCodeSource().getLocation();
        return Paths.get(location.toURI()).toString();
    }

    /**
     * Calls run() on a throwaway daemon thread so a regression that blocks
     * forever fails the test instead of hanging the build.
     */
    private static List<String> runWithDeadline(SpotBugsRunner runner, String code, long deadlineMs) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "spotbugs-runner-under-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Callable<List<String>> call = () -> runner.run(code);
            Future<List<String>> future = executor.submit(call);
            return future.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("run() did not return within " + deadlineMs
                    + "ms: the SpotBugs timeout does not bound the subprocess");
            return List.of();
        } finally {
            executor.shutdownNow();
        }
    }

    private long reportedPid() throws Exception {
        return Long.parseLong(report().get(0));
    }

    private Path reportedClassesDir() throws Exception {
        return Path.of(report().get(1));
    }

    private List<String> report() throws Exception {
        assertTrue(Files.exists(reportFile), "fake SpotBugs never reported back; report file missing");
        return Files.readAllLines(reportFile);
    }

    private static boolean awaitDead(long pid, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (!isAlive(pid)) {
                return true;
            }
            Thread.sleep(50L);
        }
        return !isAlive(pid);
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static Set<String> codecriticTempDirs() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> entries = Files.list(tmp)) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith("codecritic-"))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }
}
