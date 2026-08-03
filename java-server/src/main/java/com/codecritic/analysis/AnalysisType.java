package com.codecritic.analysis;

/**
 * Discriminator used by the strategy factory to route analysis work.
 */
public enum AnalysisType {
    COMPLEXITY("complexity-analysis"),
    BUGS("bug-detection"),
    TEST_GENERATION("test-generation");

    private final String jobType;

    AnalysisType(String jobType) {
        this.jobType = jobType;
    }

    public String jobType() {
        return jobType;
    }

    public static AnalysisType fromJobType(String jobType) {
        for (AnalysisType type : values()) {
            if (type.jobType.equals(jobType)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown job type: " + jobType);
    }
}
