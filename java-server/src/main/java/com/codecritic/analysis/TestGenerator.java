package com.codecritic.analysis;

import com.codecritic.dto.TestGenerationResponse;

/**
 * Strategy contract for JUnit test scaffold generation.
 */
public interface TestGenerator {

    TestGenerationResponse generate(String className, String methodName, String parameters, String code);
}
