package com.codecritic.analysis;

import java.util.Map;

/**
 * Strategy interface — one implementation per analysis type.
 * New analysis capabilities are added by implementing this interface and
 * registering a bean, without modifying existing strategies (OCP).
 */
public interface AnalysisStrategy {

    AnalysisType type();

    Object execute(Map<String, Object> payload);
}
