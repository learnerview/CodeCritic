package com.codecritic.analysis;

import com.codecritic.analysis.impl.BugsStrategy;
import com.codecritic.analysis.impl.CachedSpotBugsBugDetector;
import com.codecritic.analysis.impl.ComplexityStrategy;
import com.codecritic.analysis.impl.PatternBugDetector;
import com.codecritic.analysis.impl.TestGenerationStrategy;
import com.codecritic.analysis.impl.JavaParserComplexityAnalyzer;
import com.codecritic.analysis.impl.JavaParserTestGenerator;
import com.codecritic.analysis.impl.CompositeBugDetector;
import com.codecritic.analysis.impl.SpotBugsBugDetector;
import com.codecritic.analysis.impl.SpotBugsRunner;
import com.codecritic.metrics.AnalysisMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisStrategyFactoryTest {

    private final AnalysisStrategyFactory factory = new AnalysisStrategyFactory(List.of(
            new ComplexityStrategy(new JavaParserComplexityAnalyzer()),
            new BugsStrategy(new CompositeBugDetector(new PatternBugDetector(),
                    new CachedSpotBugsBugDetector(
                            new SpotBugsBugDetector(new SpotBugsRunner()), new AnalysisMetrics()))),
            new TestGenerationStrategy(new JavaParserTestGenerator())));

    @Test
    void factory_registersAllStrategies() {
        assertEquals(AnalysisType.COMPLEXITY, factory.getStrategy(AnalysisType.COMPLEXITY).type());
        assertEquals(AnalysisType.BUGS, factory.getStrategy(AnalysisType.BUGS).type());
        assertEquals(AnalysisType.TEST_GENERATION, factory.getStrategy(AnalysisType.TEST_GENERATION).type());
    }

    @Test
    void factory_unknownType_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new AnalysisStrategyFactory(List.of()).getStrategy(AnalysisType.COMPLEXITY));
    }

    @Test
    void fromJobType_mapsKnownTypes() {
        assertEquals(AnalysisType.COMPLEXITY, AnalysisType.fromJobType("complexity-analysis"));
        assertEquals(AnalysisType.BUGS, AnalysisType.fromJobType("bug-detection"));
        assertEquals(AnalysisType.TEST_GENERATION, AnalysisType.fromJobType("test-generation"));
    }

    @Test
    void fromJobType_unknownType_throws() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisType.fromJobType("unknown-job"));
    }
}
