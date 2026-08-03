package com.codecritic.analysis.impl;

import com.codecritic.dto.BugFinding;
import com.codecritic.metrics.AnalysisMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CachedSpotBugsBugDetectorTest {

    private final SpotBugsBugDetector delegate = mock(SpotBugsBugDetector.class);
    private final CachedSpotBugsBugDetector detector = new CachedSpotBugsBugDetector(delegate, new AnalysisMetrics());

    @Test
    void identicalCode_analyzedOnlyOnce() {
        List<BugFinding> findings = List.of(new BugFinding("SpotBugsFinding", 0, "line", "see output"));
        when(delegate.detect("class A {}")).thenReturn(findings);

        assertEquals(findings, detector.detect("class A {}"));
        assertEquals(findings, detector.detect("class A {}"));
        verify(delegate, times(1)).detect(anyString());
        assertEquals(1, detector.hits());
        assertEquals(1, detector.misses());
        assertEquals(1, detector.size());
    }

    @Test
    void differentCode_analyzedSeparately() {
        when(delegate.detect(anyString())).thenReturn(List.of());

        detector.detect("class A {}");
        detector.detect("class B {}");

        verify(delegate, times(2)).detect(anyString());
        assertEquals(2, detector.size());
    }

    @Test
    void cacheKey_differsByContentAndVersion() {
        String a = CachedSpotBugsBugDetector.cacheKey("class A {}");
        String b = CachedSpotBugsBugDetector.cacheKey("class B {}");
        assertNotEquals(a, b);
        assertEquals(a, CachedSpotBugsBugDetector.cacheKey("class A {}"));
        assertEquals(64, a.length());
    }

    @Test
    void stats_areAccurate() {
        when(delegate.detect(anyString())).thenReturn(List.of());

        detector.detect("code1");
        detector.detect("code1");
        detector.detect("code2");

        assertEquals(1, detector.hits());
        assertEquals(2, detector.misses());
        assertEquals(2, detector.size());
    }
}
