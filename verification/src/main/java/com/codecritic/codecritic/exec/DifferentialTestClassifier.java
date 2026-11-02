package com.codecritic.exec;

public final class DifferentialTestClassifier {

    private DifferentialTestClassifier() {
    }

    public static DifferentialVerdict classify(TestSuiteResult base, TestSuiteResult head) {
        if (base == null || head == null) {
            return DifferentialVerdict.INCONCLUSIVE;
        }

        ExecutionStatus baseStatus = base.status();
        ExecutionStatus headStatus = head.status();

        if (baseStatus == ExecutionStatus.SUCCESS && headStatus == ExecutionStatus.SUCCESS) {
            return DifferentialVerdict.P2P;
        }
        if (baseStatus == ExecutionStatus.TEST_FAILURES && headStatus == ExecutionStatus.SUCCESS) {
            return DifferentialVerdict.F2P;
        }
        if (baseStatus == ExecutionStatus.SUCCESS && headStatus == ExecutionStatus.TEST_FAILURES) {
            return DifferentialVerdict.P2F;
        }
        if (baseStatus == ExecutionStatus.TEST_FAILURES && headStatus == ExecutionStatus.TEST_FAILURES) {
            return DifferentialVerdict.F2F;
        }
        return DifferentialVerdict.INCONCLUSIVE;
    }
}
