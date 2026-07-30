package com.codecritic.service.impl;

import com.codecritic.dto.BugReport;
import com.codecritic.dto.ComplexityResponse;
import com.codecritic.dto.TestGenerationResponse;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class AnalysisServiceImplTest {

    private final AnalysisServiceImpl service = new AnalysisServiceImpl(mock(JobSubmissionService.class));

    @Test
    public void calculateComplexity_simpleIf_returnsExpected() {
        String code = "public class X { void m(){ if(true){} } }";
        ComplexityResponse r = service.calculateComplexity(code);
        assertEquals(2, r.cyclomaticComplexity());
        assertEquals(1, r.cognitiveComplexity());
    }

    @Test
    public void calculateComplexity_blank_returnsZeroes() {
        ComplexityResponse r = service.calculateComplexity("");
        assertEquals(0, r.cyclomaticComplexity());
        assertEquals(0, r.cognitiveComplexity());
    }

    @Test
    public void findBugs_detectsDivisionByZero() {
        String code = "public class Y { void m(){ int x = 1 / 0; } }";
        BugReport bugs = service.findBugs(code);
        assertTrue(bugs.bugs().stream().anyMatch(f -> "DivisionByZeroRisk".equals(f.type())));
    }

    @Test
    public void generateTest_extractsParamsAndClassName() {
        String code = "public class MyClass { public int add(int a, boolean b){ return a; } }";
        TestGenerationResponse resp = service.generateTest("", "add", "", code);
        String junit = resp.junitCode();
        assertTrue(junit.contains("MyClassTest"));
        assertTrue(junit.contains("obj.add("));
    }
}

