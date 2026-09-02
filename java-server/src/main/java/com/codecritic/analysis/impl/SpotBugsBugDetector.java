package com.codecritic.analysis.impl;

import com.codecritic.analysis.BugDetector;
import com.codecritic.dto.BugFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort SpotBugs detector. Adds richer findings when the environment
 * has a JDK and the spotbugs CLI available; otherwise returns empty.
 */
@Component
public class SpotBugsBugDetector implements BugDetector {

    private static final Logger log = LoggerFactory.getLogger(SpotBugsBugDetector.class);

    private final SpotBugsRunner spotBugsRunner;

    public SpotBugsBugDetector(SpotBugsRunner spotBugsRunner) {
        this.spotBugsRunner = spotBugsRunner;
    }

    @Override
    public List<BugFinding> detect(String code) {
        try {
            List<String> lines = spotBugsRunner.run(code);
            List<BugFinding> findings = new ArrayList<>();
            for (String line : lines) {
                BugFinding finding = parseSpotBugsLine(line);
                if (finding != null) {
                    findings.add(finding);
                }
            }
            return findings;
        } catch (SpotBugsRunner.CompilationException e) {
            log.info("SpotBugs skipped: code did not compile. {}", e.getMessage());
            String snippet = summarizeDiagnostics(e.getCompilerOutput());
            return List.of(new BugFinding(
                    "COMPILATION_ERROR",
                    0,
                    "SpotBugs cannot run because the code did not compile successfully.",
                    snippet.isEmpty() ? "Fix the compilation errors and re-run analysis." : snippet));
        } catch (Exception e) {
            log.info("SpotBugs analysis skipped or failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String summarizeDiagnostics(String compilerOutput) {
        if (compilerOutput == null || compilerOutput.isBlank()) {
            return "Fix the compilation errors and re-run analysis.";
        }
        List<String> lines = new ArrayList<>();
        for (String line : compilerOutput.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
            if (lines.size() >= 5) {
                break;
            }
        }
        return "Compiler diagnostics:\n" + String.join("\n", lines);
    }

    private BugFinding parseSpotBugsLine(String line) {
        // Robustly parse both supported SpotBugs textui outputs:
        //   tab-separated: category<TAB>priority<TAB>type<TAB>class<TAB>method<TAB>field<TAB>message
        //   human-readable default: <priority> <rank> <TYPE>: <message>  At ClassUnderTest.java:[line N]
        if (line == null || line.isBlank()) {
            return null;
        }
        line = line.trim();

        String[] tabs = line.split("\t");
        if (tabs.length >= 3) {
            String type = tabs[2].trim();
            String message = tabs.length > 6 ? tabs[6].trim() : line;
            return new BugFinding(type, 0, message, type);
        }

        int colon = line.indexOf(':');
        if (colon <= 0) {
            return new BugFinding("SpotBugsFinding", 0, line, "See SpotBugs output");
        }
        String head = line.substring(0, colon).trim();
        String message = line.substring(colon + 1).trim();
        if (head.isEmpty()) {
            return new BugFinding("SpotBugsFinding", 0, line, "See SpotBugs output");
        }

        String[] headParts = head.split("\\s+");
        String type = headParts[headParts.length - 1].trim();
        if (type.isEmpty() || type.matches("[HML]") || type.matches("[A-D]")) {
            type = "SpotBugsFinding";
        }

        int lineNumber = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[line\\s+(\\d+)\\]").matcher(line);
        if (m.find()) {
            lineNumber = Integer.parseInt(m.group(1));
        }

        return new BugFinding(type, lineNumber, message, "See SpotBugs: " + type);
    }
}
