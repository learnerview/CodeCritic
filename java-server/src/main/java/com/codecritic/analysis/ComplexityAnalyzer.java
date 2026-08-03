package com.codecritic.analysis;

import com.codecritic.dto.ComplexityResponse;

/**
 * Strategy contract for cyclomatic/cognitive complexity analysis.
 * Implementations can use AST parsing, heuristics, or any future approach.
 */
public interface ComplexityAnalyzer {

    ComplexityResponse analyze(String code);
}
