package com.codecritic.analysis.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Stand-in for the SpotBugs CLI, launched as a real child JVM by
 * {@link SpotBugsRunnerTest} so process lifecycle behaviour can be exercised
 * without a SpotBugs install.
 *
 * <p>Expected argv (the runner appends {@code -textui <classesDir>} to its
 * configured command):</p>
 * <pre>
 * arg0 = file to write "pid\nclassesDir" to
 * arg1 = mode: "hang" (print one finding, then never exit, keep stdout open)
 *         or "exit" (print header + findings, then exit)
 * </pre>
 */
public final class SpotBugsRunnerFakeProcess {

    private SpotBugsRunnerFakeProcess() {
    }

    public static void main(String[] args) throws Exception {
        String reportFile = args.length > 0 ? args[0] : null;
        String mode = args.length > 1 ? args[1] : "hang";
        // The runner always appends the classes directory as the last argument.
        String classesDir = args.length > 0 ? args[args.length - 1] : "";

        if (reportFile != null && !reportFile.isBlank()) {
            Files.writeString(Path.of(reportFile), ProcessHandle.current().pid() + "\n" + classesDir);
        }

        if ("exit".equals(mode)) {
            // Mirrors the -textui shape: a "category..." header row that must be
            // filtered out, followed by real findings that must be returned.
            System.out.println("category\tpriority\ttype\tclass\tmethod\tfield\tmessage");
            System.out.println();
            System.out.println("M C NP_ALWAYS_NULL: Possible null pointer dereference here  At Ok.java:[line 7]");
            System.out.println("H C DE_MIGHT_IGNORE: Method ignores exceptional return value  At Ok.java:[line 9]");
            System.out.flush();
            return;
        }

        System.out.println("M C NP_ALWAYS_NULL: partial finding printed before the hang");
        System.out.flush();
        // Hang forever holding stdout open: the pathology the runner's timeout
        // must survive.
        new CountDownLatch(1).await();
    }
}
