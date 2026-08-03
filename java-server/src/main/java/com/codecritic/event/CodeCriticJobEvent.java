package com.codecritic.event;

import com.codecritic.analysis.AnalysisType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Domain event published when an analysis job completes or fails.
 * Listened to by observers (logging, metrics, notifications).
 */
@Getter
@Builder
@AllArgsConstructor
public class CodeCriticJobEvent {

    private final AnalysisType type;
    private final String jobId;
    private final String status;
    private final String detail;
    private final Instant occurredAt;
}
