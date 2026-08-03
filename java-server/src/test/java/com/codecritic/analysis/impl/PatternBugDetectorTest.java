package com.codecritic.analysis.impl;

import com.codecritic.dto.BugFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatternBugDetectorTest {

    private final PatternBugDetector detector = new PatternBugDetector();

    @Test
    void detect_divisionByZero_findsRisk() {
        List<BugFinding> bugs = detector.detect("public class A { int f(){ return 10 / 0; } }");
        assertTrue(bugs.stream().anyMatch(b -> "DivisionByZeroRisk".equals(b.type())));
    }

    @Test
    void detect_nullToString_findsRisk() {
        List<BugFinding> bugs = detector.detect("public class A { String f(Object o){ return o.toString(); } }");
        assertTrue(bugs.stream().anyMatch(b -> "NullPointerRisk".equals(b.type())));
    }

    @Test
    void detect_cleanCode_noFindings() {
        List<BugFinding> bugs = detector.detect("public class A { int add(int a, int b){ return a + b; } }");
        assertTrue(bugs.isEmpty());
    }

    @Test
    void detect_blankCode_noFindings() {
        assertTrue(detector.detect("").isEmpty());
        assertTrue(detector.detect(null).isEmpty());
    }

    @Test
    void detect_reportsCorrectLineNumbers() {
        List<BugFinding> bugs = detector.detect("public class A {\n  int f(){ return 1 / 0; }\n}");
        assertTrue(bugs.stream().anyMatch(b -> b.line() == 2));
    }
}
