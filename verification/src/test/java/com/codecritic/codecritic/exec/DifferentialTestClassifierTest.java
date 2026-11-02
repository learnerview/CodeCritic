package com.codecritic.exec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DifferentialTestClassifierTest {

    @Test
    void classifiesFailToPass() {
        assertThat(DifferentialTestClassifier.classify(
                result(ExecutionStatus.TEST_FAILURES),
                result(ExecutionStatus.SUCCESS)))
                .isEqualTo(DifferentialVerdict.F2P);
    }

    @Test
    void classifiesPassToFail() {
        assertThat(DifferentialTestClassifier.classify(
                result(ExecutionStatus.SUCCESS),
                result(ExecutionStatus.TEST_FAILURES)))
                .isEqualTo(DifferentialVerdict.P2F);
    }

    @Test
    void classifiesPassToPassAndFailToFail() {
        assertThat(DifferentialTestClassifier.classify(
                result(ExecutionStatus.SUCCESS),
                result(ExecutionStatus.SUCCESS)))
                .isEqualTo(DifferentialVerdict.P2P);
        assertThat(DifferentialTestClassifier.classify(
                result(ExecutionStatus.TEST_FAILURES),
                result(ExecutionStatus.TEST_FAILURES)))
                .isEqualTo(DifferentialVerdict.F2F);
    }

    @Test
    void classifiesCompileAndTimeoutAsInconclusive() {
        assertThat(DifferentialTestClassifier.classify(
                result(ExecutionStatus.COMPILE_FAILURE),
                result(ExecutionStatus.SUCCESS)))
                .isEqualTo(DifferentialVerdict.INCONCLUSIVE);
        assertThat(DifferentialTestClassifier.classify(
                result(ExecutionStatus.TIMEOUT),
                result(ExecutionStatus.SUCCESS)))
                .isEqualTo(DifferentialVerdict.INCONCLUSIVE);
        assertThat(DifferentialTestClassifier.classify(null, result(ExecutionStatus.SUCCESS)))
                .isEqualTo(DifferentialVerdict.INCONCLUSIVE);
    }

    private static TestSuiteResult result(ExecutionStatus status) {
        return TestSuiteResult.builder("HEAD", "maven", "mvn test", status)
                .counts(1, status == ExecutionStatus.TEST_FAILURES ? 1 : 0, 0, 0)
                .durationMillis(100)
                .build();
    }
}
