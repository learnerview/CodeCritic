package com.codecritic.analysis;

import com.codecritic.dto.BugFinding;

import java.util.List;

/**
 * Strategy contract for bug detection. Implementations scan Java source and
 * return zero or more findings.
 */
public interface BugDetector {

    List<BugFinding> detect(String code);
}
